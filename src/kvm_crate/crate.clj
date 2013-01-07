(ns kvm-crate.crate
  "Crate with functions for setting up and configuring KVM servers and guests"
  (:require
   [pallet.actions :as actions]
   [pallet.crate :as crate]
   [pallet.utils :as utils]
   [pallet.crate.sudoers :as sudoers]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.environment :as env])
  (:use [pallet.crate :only [def-plan-fn]]))

(def-plan-fn install-deb
  "Install a single deb by transferring local file and installing via dpkg"
  [deb-name]
  [tmp-path (m-result (str "/tmp/" deb-name))
   local-file (m-result (utils/resource-path (str "custom-debs/" deb-name)))]
  ;; transfer file and install it via dpkg
  (actions/remote-file tmp-path :local-file local-file)
  (actions/exec-checked-script
   "install my custom debs (these will fail, but are triggered later)"
   (dpkg -i ~tmp-path)
   (exit 0)))

(def-plan-fn install-custom-debs
  "Install all needed custom debs for KVM server using openvswitch"
  []
  [custom-debs (m-result ["libvirt0_1.0.0-0ubuntu4_amd64.deb"
                          "libvirt0-dbg_1.0.0-0ubuntu4_amd64.deb"
                          "libvirt-bin_1.0.0-0ubuntu4_amd64.deb"
                          "libvirt-dev_1.0.0-0ubuntu4_amd64.deb"
                          "libvirt-doc_1.0.0-0ubuntu4_all.deb"])]
  (m-result (map install-deb custom-debs)))

(def-plan-fn configure-openvswitch
  "Configure openvswtich for KVM server."
  []
  [])

(def-plan-fn configure-server
  "Install packages for KVM server and configure networking"
  []
  [node-hostname crate/target-name
   host-config (env/get-environment [:host-config node-hostname])
   admin-username (m-result (get-in host-config [:admin-user :username]))]

  ;; install packages
  (install-custom-debs)
  (actions/packages :aptitude ["kvm" "ubuntu-virt-server" "python-vm-builder"
                               "openvswitch-controller" "openvswitch-brcompat"
                               "openvswitch-switch" "openvswitch-datapath-source"])

  ;; setup openvswitch
  (configure-openvswitch))
