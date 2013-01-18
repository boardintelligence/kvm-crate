(ns kvm-crate.specs
  "Server and group specs for working with KVM servers and guests"
  (:require
   [pallet.api :as api]
   [kvm-crate.crate.server :as kvm-s]))

(def
  ^{:doc "Server spec for a KVM server (host)."}
  kvm-server
  (api/server-spec
   :phases
   {:configure (api/plan-fn (kvm-s/configure-server))
    :create-guest-vm (api/plan-fn (kvm-s/create-guest-vm))}))

(def
  ^{:doc "Spec for a server acting as DHCP for private network."}
  kvm-dhcp-server
  (api/server-spec
   :phases
   {:configure (api/plan-fn (kvm-s/configure-dhcp-server))
    :update-dhcp-config (api/plan-fn (kvm-s/update-dhcp-config))}))

(def
  ^{:doc "Spec for a server acting as KVM image server."}
  kvm-image-server
  (api/server-spec
   :phases
   {;;:configure (api/plan-fn (kvm-s/congigure-image-server))
    ;;:create-image (api/plan-fn (kvm-s/create-image))
    }))

(def
  ^{:doc "Spec for a KVM guest VM."}
  kvm-guest-vm
  (api/server-spec
   :phases
   {;;:firstboot (api/plan-fn (kvm/setup-guest-vm-firstboot))
    }))
