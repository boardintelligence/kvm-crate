(ns kvm-crate.api
  (:require
   [pallet.algo.fsmop :as fsmop]
   [pallet.node :as node]
   [pallet.configure :as configure]
   [pallet.compute :as compute]
   [pallet.api :as api]
   [pallet-nodelist-helpers :as helpers]
   [kvm-crate.crate.server :as server-crate]))

(defn host-is-kvm-server?
  "Check if a host is a KVM server (as understood by the kvm-create)"
  [hostname]
  (helpers/host-has-phase? hostname :configure-kvm-server))

(defn configure-kvm-server
  "Set up a machine to act as a KVM server"
  [hostname]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-kvm-server? hostname)
    (throw (IllegalArgumentException. (format "%s is not a kvm-server!" hostname))))
  (let [result (helpers/lift-one-node-and-phase hostname :configure-kvm-server)]
    (when (fsmop/failed? result)
      (throw (IllegalStateException. "Failed to configure KVM server!")))
    result))

(defn- add-gre-port
  "Add a single GRE port for a KVM server"
  [hostname gre-config]
  (let [result (helpers/run-one-plan-fn hostname server-crate/add-gre-port {:gre-config gre-config})]
    (when (fsmop/failed? result)
      (throw (IllegalStateException. (format "Failed to add GRE port %s to KVM server %s!"
                                             (:iface gre-config)
                                             hostname))))
    result))

(defn- add-gre-ports
  "Add the relevant GRE ports for a KVM server."
  [[hostname config]]
  ;; note: we need the doall since map is lazy and the outer
  ;; binding will be lost if we wait to kick it off
  (doall (map (partial add-gre-port hostname) (:gre-connections config))))

(defn connect-kvm-servers
  "Connect the OVS of KVM servers using GRE+IPSec"
  []
  (helpers/ensure-nodelist-bindings)
  ;; note: we need the doall since map is lazy and the outer
  ;; binding will be lost if we wait to kick it off
  (doall (map add-gre-ports helpers/*nodelist-hosts-config*)))
