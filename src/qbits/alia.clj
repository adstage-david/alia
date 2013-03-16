(ns qbits.alia
  (:require
   [clojure.core.memoize :as memo]
   [clojure.core.typed :as t]
   [qbits.knit :as knit]
   [qbits.hayt :as hayt]
   [qbits.alia.types :refer :all]
   [qbits.alia.codec :as codec]
   [qbits.alia.codec.eaio-uuid]
   [qbits.alia.utils :as utils]
   [qbits.alia.cluster-options :as copt])
  (:import
   [clojure.lang Sequential PersistentList APersistentVector]
   [com.datastax.driver.core
    BoundStatement
    Cluster
    Cluster$Builder
    ConsistencyLevel
    PreparedStatement
    Query
    ResultSet
    ResultSetFuture
    Session
    SimpleStatement]
   [com.google.common.util.concurrent
    Futures
    FutureCallback]
   [java.nio ByteBuffer]
   [java.util.concurrent ExecutorService]))


(t/ann *consistency* (t/Option ConsistencyValue))
(t/ann consistency-levels (HMap {ConsistencyLevel ConsistencyValue}))
(t/ann set-consistency! [ConsistencyValue -> nil])
(def ^:dynamic *consistency* :one)
(def consistency-levels (utils/enum-values->map (ConsistencyLevel/values)))

(defmacro with-consistency
  "Binds qbits.alia/*consistency*"
  [consistency & body]
  `(binding [qbits.alia/*consistency* ~consistency]
     ~@body))

(def set-consistency!
  "Sets root value of *consistency*"
  (utils/var-root-setter *consistency*))

(t/ann *session* (t/Option Session))
(def ^:dynamic *session*)

(defmacro with-session
  "Binds qbits.alia/*session*"
  [session & body]
  `(binding [qbits.alia/*session* ~session]
     ~@body))

(t/ann set-session! [Session -> nil])
(def set-session!
  "Sets root value of *session*"
  (utils/var-root-setter *session*))

(t/ann *executor* (t/Option ExecutorService))
(def ^:dynamic *executor* (knit/executor :cached))

(t/ann set-executor! [ExecutorService -> nil])
(def set-executor!
  "Sets root value of *executor*"
  (utils/var-root-setter *executor*))

(defmacro with-executor
  "Binds qbits.alia/*executor*"
  [executor & body]
  `(binding [qbits.alia/*executor* ~executor]
     ~@body))

(t/ann *keywordize* Boolean)
(def ^:dynamic *keywordize* false)

(t/ann set-keywordize! [Boolean -> nil])
(def set-keywordize!
  "Sets root value of *keywordize*"
  (utils/var-root-setter *keywordize*))

(def ^:dynamic *hayt-raw-fn* (memo/memo-lu hayt/->raw 100))
(def set-hayt-raw-fn!
  "Sets root value of *hayt-raw-fn*, allowing to change
   the cache factory, defaults to LU with a threshold of 100"
  (utils/var-root-setter *hayt-raw-fn*))

(t/ann cluster [(Sequential* String) ;; broken, will barf on seqs!
                Any * ;; to be replaced with kw type when it is implemented in core.typed
                -> Cluster])
(defn cluster
  "Returns a new com.datastax.driver.core/Cluster instance"
  [hosts & {:as options}]
  (-> (Cluster/builder)
      (copt/set-cluster-options! (assoc options :contact-points hosts))
      .build))

(t/ann connect (Fn [Cluster String -> Session]
                   [Cluster -> Session]))
(defn ^Session connect
  "Returns a new com.datastax.driver.core/Session instance. We need to
have this separate in order to allow users to connect to multiple
keyspaces from a single cluster instance"
  ([^Cluster cluster keyspace]
     (.connect cluster keyspace))
  ([^Cluster cluster]
     (.connect cluster)))

(t/ann shutdown [(U Cluster Session) -> nil])
(defn shutdown
  "Shutdowns Session or Cluster instance, clearing the underlying
pools/connections"
  ([cluster-or-session]
     (.shutdown cluster-or-session))
  ([]
     (shutdown *session*)))

(t/ann prepare (Fn [Session String -> PreparedStatement]
                   [String -> PreparedStatement]))
(defn prepare
  "Returns a com.datastax.driver.core.PreparedStatement instance to be
used in `execute` after it's been bound with `bind`"
  ([^Session session ^String query]
     (.prepare session query))
  ([query]
     (prepare *session* query)))

(t/ann bind [PreparedStatement -> (Sequential* Any)])
(defn bind
  "Returns a com.datastax.driver.core.BoundStatement instance to be
  used with `execute`"
  [^PreparedStatement prepared-statement values]
  (.bind prepared-statement (to-array (map codec/encode values))))

;; http://clojure-doc.org/articles/ecosystem/core_typed/mm_protocol_datatypes.html
(defprotocol PStatement
  (query->statement [q values] "Encodes input into a Statement (Query) instance"))

(extend-protocol PStatement
  Query
  (query->statement [q _] q)

  PreparedStatement
  (query->statement [q values]
    (bind q values))

  String
  (query->statement [q _]
    (SimpleStatement. q))

  clojure.lang.IPersistentMap
  (query->statement [q _]
    (query->statement (*hayt-raw-fn* q) nil)))

(defn ^:private set-statement-options!
  [^Query statement routing-key retry-policy tracing? consistency]
  (when routing-key
    (.setRoutingKey ^SimpleStatement statement
                    ^ByteBuffer routing-key))
  (when retry-policy
    (.setRetryPolicy statement retry-policy))
  (when tracing?
    (.enableTracing statement))

  (.setConsistencyLevel statement (consistency-levels consistency)))

(t/ann execute (Fn [Session CQLQuery Any * ;; to be replaced with :kw opts impl of core.typed when its ready
                    -> CQLResult]
                   [CQLQuery Any * ;; to be replaced with :kw opts impl of core.typed when its ready
                    -> CQLResult]))
(defn execute
  "Executes querys against a session. Returns a collection of rows.
The first argument can be either a Session instance or the query
directly.

So 2 signatures:

 [session query & {:keys [consistency routing-key retry-policy
                          tracing? keywordize? values]
                  :or {executor default-async-executor
                       consistency *consistency*
                       keywordize? *keywordize*}}]

or

 [query & {:keys [consistency routing-key retry-policy
                  tracing? keywordize? values]
                  :or {executor default-async-executor
                       consistency *consistency*
                       keywordize? *keywordize*}}]

If you chose the latter the Session must be bound with
`with-session`."
  [& args]
  (let [[^Session session query & {:keys [consistency routing-key retry-policy
                                          tracing? values keywordize?]
                                   :or {consistency *consistency*
                                        keywordize? *keywordize*}}]
        (if (even? (count args))
          args
          (conj args *session*))
        ^Query statement (query->statement query values)]
    (set-statement-options! statement routing-key retry-policy tracing? consistency)
    (codec/result-set->maps (.execute session statement) keywordize?)))

(t/ann execute-async (Fn [Session CQLQuery Any * ;; to be replaced with :kw opts impl of core.typed when its ready
                          -> CQLAsyncResult]
                         [CQLQuery Any * ;; to be replaced with :kw opts impl of core.typed when its ready
                          -> CQLAsyncResult]))
(defn execute-async
  "Same as execute, but returns a promise and accepts :success and :error
  handlers, you can also pass :executor for the ResultFuture, it
  defaults to a cachedThreadPool if you don't"
  [& args]
  (let [[^Session session query & {:keys [success error executor consistency
                                          routing-key retry-policy tracing?
                                          values keywordize?]
                                   :or {executor *executor*
                                        consistency *consistency*
                                        keywordize? *keywordize*}}]
        (if (even? (count args))
          args
          (conj args *session*))
        ^Query statement (query->statement query values)]
    (set-statement-options! statement routing-key retry-policy tracing? consistency)
    (let [^ResultSetFuture rs-future (.executeAsync session statement)
          async-result (promise)]
      (Futures/addCallback
       rs-future
       (reify FutureCallback
         (onSuccess [_ result]
           (let [result (codec/result-set->maps (.get rs-future) keywordize?)]
             (deliver async-result result)
             (when (fn? success)
               (success result))))
         (onFailure [_ err]
           (deliver async-result err)
           (when (fn? error)
             (error err))))
       executor)
      async-result)))
