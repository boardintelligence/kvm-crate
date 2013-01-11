(ns kvm-crate.api
  (:require
   [pallet.algo.fsmop :as fsmop]
   [pallet.node :as node]
   [pallet.configure :as configure]
   [pallet.compute :as compute]
   [pallet.api :as api]
   [pallet-nodelist-helpers :as helpers]))

(defn host-is-kvm-server?
  "Check if a host is a KVM server (as understood by the kvm-create)"
  [hostname]
  (helpers/host-has-phase? hostname :configure-kvm-server))

(defn configure-kvm-server
  "Set up a machine to act as a KVM server"
  [hostname]
  (println (format "Configuring KVM server for %s.." hostname))
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-kvm-server? hostname)
    (throw (IllegalArgumentException. (format "%s is not a kvm-server!" hostname))))
  (when (fsmop/failed?
         (helpers/lift-one-node-and-phase hostname :configure-kvm-server))
    (throw (IllegalStateException. "Failed to configure KVM server!"))))
