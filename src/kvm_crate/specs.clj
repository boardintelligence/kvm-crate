(ns kvm-crate.specs
  "Server and group specs for working with KVM servers and guests"
  (:require
   [pallet.api :as api]
   [kvm-crate.crate.server :as kvm-s]))

(def
  ^{:doc "Server spec for a KVM server (host)"}
  kvm-server
  (api/server-spec
   :phases
   {:configure-kvm-server (api/plan-fn (kvm-s/configure-server))
    ;;:create-guest-vm-user (api/plan-fn (kvm/create-guest-vm-user))
    ;;:create-guest-vm (api/plan-fn (kvm/create-guest-vm))
    ;;:create-guest-vm-upstart (api/plan-fn (kvm/create-guest-vm-upstart))
    }))

(def
  ^{:doc "Group spec for a KVM server (host)"}
  kvm-server-g
  (api/group-spec
   "kvm-server-g"
   :extends [kvm-server]))

(def
  ^{:doc "Spec for a KVM guest server"}
  kvm-guest-vm
  (api/server-spec
   :phases
   {;;:firstboot (api/plan-fn (kvm/setup-guest-vm-firstboot))
    }))
