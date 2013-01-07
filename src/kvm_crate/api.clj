(ns kvm-crate.api
  (:require
   [pallet.algo.fsmop :as fsmop]
   [pallet.node :as node]
   [pallet.configure :as configure]
   [pallet.compute :as compute]
   [pallet.api :as api]))

(def ^{:dynamic true
       :doc "Dynamic var epxected to be bound to a map of KVM hosts config.
            The format of the map is described in the README file."}
  *kvm-hosts-config* nil)

(def ^{:dynamic true
       :doc "Dynamic var epxected to be bound to a map to use as pallet env."}
  *pallet-environment* nil)

(def ^{:dynamic true
       :doc "Dynamic var epxected to be bound to a computeservice."}
  *compute-service* nil)

(defn- node-list-info-for-host
  "Returns a single host info vector"
  [[hostname host-config]]
  [hostname (:group-name (:group-spec host-config)) (:ip host-config) :ubuntu])

(defn- generate-node-list
  "Transform the host config hash into the format expected by pallet"
  [config]
  (vec (map node-list-info-for-host config)))

(defn node-list-compute-service
  "Create a node-list compute service based on KVM servers and guests."
  [config]
  (configure/compute-service "node-list" :node-list (generate-node-list config)))

(defmacro with-config
  "Utility function to wrap operations to use a particular KVM config."
  [[hosts-config pallet-environment] & body]
  `(binding [*kvm-hosts-config* ~hosts-config
             *compute-service* (node-list-compute-service ~hosts-config)
             *pallet-environment* ~pallet-environment]
     ~@body))

(defn ensure-bindings []
  "Ensure the relevant bindings for using KVM api is in place."
  (when-not (instance? clojure.lang.IPersistentMap *kvm-hosts-config*)
    (throw (IllegalArgumentException. "*kvm-hosts-config* is not a map")))
  (when-not (instance? clojure.lang.IPersistentMap *pallet-environment*)
    (throw (IllegalArgumentException. "*pallet-environment* is not a map")))
  (when (nil? *compute-service*)
    (throw (IllegalArgumentException. "*compute-service* is nil"))))

(defn- get-group-spec
  [hostname]
  (get-in *kvm-hosts-config* [hostname :group-spec]))

(defn- node-for-hostname
  [hostname]
  (first (filter #(= (:name %) hostname) (compute/nodes *compute-service*))))

(defn- get-admin-user
  [hostname & {:keys [sudo-user]}]
  (let [admin-username (get-in *kvm-hosts-config* [hostname :admin-user :username])
        ssh-public-key-path (get-in *kvm-hosts-config* [hostname :admin-user :ssh-public-key-path])
        ssh-private-key-path (get-in *kvm-hosts-config* [hostname :admin-user :ssh-private-key-path])
        passphrase (.getBytes (get-in *kvm-hosts-config* [hostname :admin-user :passphrase]))]
    (if (= admin-username "root")
         (api/make-user admin-username
                        :public-key-path ssh-public-key-path
                        :private-key-path ssh-private-key-path
                        :passphrase passphrase
                        :no-sudo true)
         (api/make-user admin-username
                        :public-key-path ssh-public-key-path
                        :private-key-path ssh-private-key-path
                        :passphrase passphrase
                        :sudo-user sudo-user))))

(defn lift-one-node-and-phase
  "Lift a given host, applying only one specified phase"
  ([hostname phase] (lift-one-node-and-phase hostname (get-admin-user hostname) phase {}))
  ([hostname phase env-options] (lift-one-node-and-phase hostname (get-admin-user hostname) phase env-options))
  ([hostname user phase env-options]
     (let [spec (get-group-spec hostname)
           node (node-for-hostname hostname)
           result (api/lift
                   {spec node}
                   :environment (merge *pallet-environment* env-options {:host-config *kvm-hosts-config*})
                   :phase phase
                   :user user
                   :compute *compute-service*)]
       (fsmop/wait-for result)
       (when (fsmop/failed? result)
         (do
           ;; TODO: use logger here
           (println "Errors encountered:")
           (fsmop/report-operation result)))
       result)))

(defn host-is-kvm-server?
  "Check if a host is a KVM server (as understood by the kvm-create)"
  [hostname]
  (contains? (:phases (get-group-spec hostname)) :configure-kvm-server))

(defn configure-kvm-server
  "Set up a machine to act as a KVM server"
  [hostname]
  (println (format "Configuring KVM server for %s.." hostname))
  (ensure-bindings)
  (when-not (host-is-kvm-server? hostname)
    (throw (IllegalArgumentException. (format "%s is not a kvm-server!" hostname))))
  (when (fsmop/failed? (lift-one-node-and-phase hostname
                                                :configure-kvm-server))
    (throw (IllegalStateException. "Failed to configure KVM server!"))))
