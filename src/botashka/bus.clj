(ns botashka.bus
  (:require [clojure.core.async :as async]))

(defn new-bus
  "Creates an event bus separating high-frequency streaming events and canonical run events.
   `in` channel receives messages with shape {:topic ... :stream-id ... :kind ... :payload ...}.
   Topic extraction uses :topic by default."
  ([] (new-bus 256))
  ([buffer-size]
   (let [in (async/chan (async/sliding-buffer buffer-size))
         publication (async/pub in :topic)]
     {:in in
      :pub publication})))

(defn publish!
  "Publishes a message to the bus.
   `topic`: :canonical or :stream
   `stream-id`: stream identifier, e.g. \"main\" or tool-call-id
   `kind`: :token, :stdout, :stderr, :event
   `payload`: arbitrary data value"
  [{:keys [in]} topic stream-id kind payload]
  (when in
    (async/put! in {:topic topic
                    :stream-id stream-id
                    :kind kind
                    :payload payload})))

(defn publish-canonical!
  "Convenience helper to publish a canonical event onto the bus."
  [bus event]
  (publish! bus :canonical "main" :event event))

(defn publish-stream!
  "Convenience helper to publish an ephemeral streaming item onto the bus."
  [bus stream-id kind payload]
  (publish! bus :stream stream-id kind payload))

(defn subscribe!
  "Subscribes a channel to a specific topic on the bus.
   Returns the output channel."
  ([bus topic] (subscribe! bus topic 100))
  ([{:keys [pub]} topic buf-or-n]
   (let [ch (if (number? buf-or-n)
              (async/chan buf-or-n)
              (async/chan buf-or-n))]
     (async/sub pub topic ch)
     ch)))

(defn unsubscribe!
  "Unsubscribes a channel from a topic."
  [{:keys [pub]} topic ch]
  (async/unsub pub topic ch))

(defn close-bus!
  "Closes the input bus channel and unblocks subscribers."
  [{:keys [in]}]
  (when in (async/close! in)))
