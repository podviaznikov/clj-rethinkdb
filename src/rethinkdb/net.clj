(ns rethinkdb.net
  (:require [clojure.data.json :as json]
            [rethinkdb.query-builder :refer [parse-query]]
            [rethinkdb.response :refer [parse-response]]
            [rethinkdb.utils :refer [str->bytes int->bytes bytes->int pp-bytes]]))

(defn send-int [out i n]
  (.write out (int->bytes i n) 0 n))

(defn send-str [out s]
  (let [n (count s)]
    (.write out (str->bytes s) 0 n)))

(defn read-str [in n]
  (let [resp (byte-array n)]
    (.readFully in resp 0 n)
    (String. resp)))

(defn read-init-response [in]
  (let [resp (byte-array 4096)]
    (.read in resp 0 4096)
    (clojure.string/replace (String. resp) #"\W*$" "")))

(defn read-response [in token]
  (let [recvd-token (byte-array 8)
        length (byte-array 4)]
    (.read in recvd-token 0 8)
    (let [recvd-token (bytes->int recvd-token 8)]
      (assert (= token recvd-token)))
    (.read in length 0 4)
    (let [length (bytes->int length 4)
          json (read-str in length)]
      (json/read-str json :key-fn keyword))))

(defn send-query [conn token query]
  (let [json (json/write-str query)
        {:keys [in out]} @conn
        n (count json)]
    (send-int out token 8)
    (send-int out n 4)
    (send-str out json)
    (let [{type :t resp :r} (read-response in token)
          resp (parse-response resp)]
      (condp get type
        #{1} (first resp)
        #{2} (do
               (swap! conn update-in [:waiting] #(disj % token))
               (with-meta resp {:token token}))
        #{3 5} (do
                 (swap! conn update-in [:waiting] #(conj % token))
                 (with-meta (lazy-cat resp (send-query conn token (parse-query :CONTINUE))) {:token token}))
        (throw (Exception. (first resp)))))))

(defn send-start-query [conn token args]
  (send-query conn token (parse-query :START args)))

(defn send-stop-query [conn token]
  (send-query conn token (parse-query :STOP)))
