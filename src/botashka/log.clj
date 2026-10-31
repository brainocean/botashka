(ns botashka.log
  (:import [java.time Instant]
           [java.util UUID]))

(defn- default-clock
  []
  (str (Instant/now)))

(defn- new-run-id
  []
  (str (UUID/randomUUID)))

(defn recorder
  "Creates a run-scoped recorder for normalized execution events."
  [{:keys [run-id clock]
    :or {clock default-clock}}]
  {:run-id (or run-id (new-run-id))
   :clock clock
   :events (atom [])})

(defn emit!
  "Appends one normalized execution fact and returns it.

   `evidence` is local diagnostic material. Callers MUST NOT pass it to an
   observation projection without applying its own capture and redaction policy."
  ([recorder type data]
   (emit! recorder type data nil))
  ([recorder type data evidence]
   (let [event (cond-> {:run-id (:run-id recorder)
                        :seq (count @(:events recorder))
                        :at ((:clock recorder))
                        :type type
                        :data data}
                 evidence (assoc :evidence evidence))]
     (swap! (:events recorder) conj event)
     event)))

(defn events
  "Returns the ordered execution facts recorded for one run."
  [recorder]
  @(:events recorder))
