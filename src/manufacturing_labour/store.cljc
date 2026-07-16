(ns manufacturing-labour.store
  "Manufacturing Labour Store — the append-only audit ledger and persistent
  state for the ISCO-08 9329 (Manufacturing Labourers Not Elsewhere
  Classified) independent manufacturing support labour practice actor.
  Implements the Store protocol for practitioner/worksite identity
  verification and coordination-record management.

  Pure data only: no network, no filesystem, no real equipment control.")

(defprotocol Store
  "Store protocol for the manufacturing labour actor's state and audit ledger."
  (practitioner [store practitioner-id]
    "Retrieve a registered independent practitioner record by ID. Returns nil
     if not found.")
  (worksite [store worksite-id]
    "Retrieve a worksite/facility record by ID. Returns nil if not found.")
  (register-practitioner! [store practitioner-id practitioner-data]
    "Register an independent practitioner (adds to store, returns updated store).")
  (register-worksite! [store worksite-id worksite-data]
    "Register a worksite/facility (adds to store, returns updated store).")
  (add-record! [store record-type record-data]
    "Append an immutable coordination record (work-assignment log,
     safety-briefing acknowledgment, task handoff, safety-concern flag) to
     the audit ledger. Returns updated store.")
  (records [store]
    "Return all records in the audit ledger (immutable)."))

(defrecord MemStore [practitioners worksites ledger]
  Store
  (practitioner [this practitioner-id]
    (get practitioners practitioner-id))
  (worksite [this worksite-id]
    (get worksites worksite-id))
  (register-practitioner! [this practitioner-id practitioner-data]
    (MemStore. (assoc practitioners practitioner-id practitioner-data) worksites ledger))
  (register-worksite! [this worksite-id worksite-data]
    (MemStore. practitioners (assoc worksites worksite-id worksite-data) ledger))
  (add-record! [this record-type record-data]
    (let [record (assoc record-data
                         :type record-type
                         :timestamp #?(:clj (System/currentTimeMillis)
                                       :cljs (.getTime (js/Date.))))]
      (MemStore. practitioners worksites (conj ledger record))))
  (records [this]
    ledger))

(defn create-store
  "Create a new in-memory store for the manufacturing labour actor."
  []
  (MemStore. {} {} []))
