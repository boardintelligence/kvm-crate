(ns kvm-crate.crate.server
  "Crate with functions for setting up and configuring KVM servers and guests"
  (:require
   [pallet.actions :as actions]
   [pallet.crate :as crate]
   [pallet.utils :as utils]
   [pallet.crate.sudoers :as sudoers]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.etc-hosts :as etc-hosts]
   [pallet.environment :as env])
  (:use [pallet.crate :only [def-plan-fn]]))

(def-plan-fn install-standard-packages
  "Install all needed standard packages."
  []
  (actions/package-manager :update)
  (actions/packages :aptitude ["kvm" "ubuntu-virt-server" "python-vm-builder"
                               "openvswitch-brcompat" "openvswitch-common"
                               "openvswitch-controller" "openvswitch-datapath-dkms"
                               "openvswitch-ipsec" "openvswitch-pki"
                               "openvswitch-switch" "openvswitch-test"
                               "libnetcf1" "libxen-dev"]))

(def-plan-fn install-custom-deb
  "Install a single deb by transferring local file and installing via dpkg."
  [deb-name]
  [tmp-path (m-result (str "/tmp/" deb-name))
   local-file (m-result (utils/resource-path (str "custom-debs/" deb-name)))]

  ;; transfer file and install it via dpkg
  (actions/remote-file tmp-path :local-file local-file :literal true)
  (actions/exec-checked-script
   "install my custom deb (will fail, but are triggered later)"
   (dpkg -i --force-confnew ~tmp-path)))

(def-plan-fn remove-libvirt-network
  "Remove the default network installed by libvirt."
  []
  (actions/exec-checked-script
   "Remove default libvirt network and stop libvirt+kvm services"
   (if (= @(pipe (virsh net-list)
                 (grep default)
                 (wc -l))
          1)
     (do
       (virsh net-destroy default)
       (virsh net-undefine default)))
   (service libvirt-bin stop)
   (service qemu-kvm stop)))

(def-plan-fn remove-ebtables
  "Remove ebtables, not needed."
  []
  (actions/exec-checked-script
   "Remove ebtables package, not needed"
   (aptitude --assume-yes purge ebtables)))

(def-plan-fn install-etc-interfaces
  "Install our custom /etc/network/interfaces."
  []
  [node-hostname crate/target-name
   host-config (env/get-environment [:host-config node-hostname])
   interfaces-file (m-result (:interfaces-file host-config))
   interface-config (m-result (:interface-config host-config))]
  (actions/remote-file "/etc/network/interfaces"
                       :literal true
                       :template interfaces-file
                       :values interface-config))

(def-plan-fn install-failsafe-conf
  "Install custom /etc/init/failsafe.conf to shorted boot time."
  []
  ;; shorten timeouts in /etc/init/failsafe.conf for quick reboot
  (actions/remote-file "/etc/init/failsafe.conf"
                       :local-file (utils/resource-path "ovs/failsafe.conf")
                       :literal true))

(def-plan-fn perform-ovs-setup
  "Perform ovs-vsctl steps to setup OVS bridge and ports."
  []
  [node-hostname crate/target-name
   ovs-setup (env/get-environment [:host-config node-hostname :ovs-setup])]

  (actions/remote-file "/tmp/ovs-setup.sh"
                       :local-file ovs-setup
                       :mode "0755"
                       :literal true)

  (actions/exec-checked-script
   "Run OVS setup script"
   ;; Note: we're using at here in order to avoid a failure when the
   ;;       connection to host goes down.
   (at -f "/tmp/ovs-setup.sh -M now + 1 minute")
   (pipe (echo "\"service libvirt-bin start;service qemu-kvm start\"")
         (at -M "now + 2 minutes"))))

(def-plan-fn configure-openvswitch
  "Configure openvswtich for KVM server."
  []
  (install-etc-interfaces)
  (install-failsafe-conf)
  (remove-libvirt-network)
  (remove-ebtables)
  (perform-ovs-setup))

(def-plan-fn configure-server
  "Install packages for KVM server and configure networking."
  []
  (install-standard-packages)
  (configure-openvswitch))

(def-plan-fn add-gre-port
  "Add a GRE port for a given bridge to a given remote ip."
  []
  [bridge (env/get-environment [:gre-config :bridge])
   iface (env/get-environment [:gre-config :iface])
   remote-ip (env/get-environment [:gre-config :remote-ip])
   psk (env/get-environment [:gre-config :psk])
   options (m-result (format "options:remote_ip=%s options:psk=\"%s\"" remote-ip psk))]
  (actions/exec-checked-script
   "Add GRE port"
   (ovs-vsctl -- --if-exists del-port ~bridge ~iface)
   (ovs-vsctl add-port ~bridge ~iface
              -- set interface ~iface type=ipsec_gre ~options)))

(def-plan-fn add-host-to-hosts
  "Add a single host."
  [[hostname config]]
  (etc-hosts/host (:private-ip config) (:private-hostname config)))

(defn is-static-ip?
  "Does host have a statically assigned IP"
  [config]
  (and (not (nil? (:ip config)))
       (nil? (:mac config))))

(def-plan-fn update-hosts-file
  "Update the hosts file to include all non-DHCP IPs"
  []
  [node-hostname crate/target-name
   config (env/get-environment [:host-config node-hostname])
   ip (m-result (:ip config))
   static-ips (env/get-environment [:static-ips])]

  ;; add entry for the host itself
  (etc-hosts/host ip node-hostname)
  ;; then the statis IPs
  (map add-host-to-hosts static-ips)
  etc-hosts/hosts)

(defn- is-dhcp-ip?
  "Is IP to be assinged by DHCP?"
  [config]
  (and (not (nil? (:ip config)))
       (not (nil? (:mac config)))))

(defn- dhcp-hosts-content
  [hosts-config]
  (let [dhcp-ips (filter (fn [[host config]]
                           (is-dhcp-ip? config))
                         hosts-config)]
    (reduce str "" (map (fn [[host config]]
                          (format "%s,%s,%s,infinite\n"
                                  (:mac config)
                                  (:ip config)
                                  host))
                        dhcp-ips))))

(def-plan-fn update-dhcp-config
  []
  [node-hostname crate/target-name
   hosts-config (env/get-environment [:host-config])
   hosts-file-content (m-result (dhcp-hosts-content hosts-config))
   opts-file (env/get-environment [:host-config node-hostname :dnsmasq-optsfile])]

  (actions/remote-file "/etc/ovs-net-dnsmasq.opts"
                       :local-file opts-file
                       :literal true)
  (actions/remote-file "/etc/ovs-net-dnsmasq.hosts"
                       :content hosts-file-content
                       :mode "0644"
                       :literal true)
  (actions/exec-checked-script
   "kill -HUP dnsmasq"
   ("if [ -f /var/run/ovs-net-dnsmasq.pid ];then kill -HUP `cat /var/run/ovs-net-dnsmasq.pid`;fi")))

(def-plan-fn install-dnsmasq-upstart-job
  []
  [node-hostname crate/target-name
   interface (env/get-environment [:host-config node-hostname :dhcp-interface])]
  (actions/remote-file "/etc/init/ovs-net-dnsmasq.conf"
                       :literal true
                       :template "ovs/ovs-net-dnsmasq.conf"
                       :values {:interface interface})
  (actions/exec-checked-script
   "Start dnsmasq for priate network"
   (start ovs-net-dnsmasq)))

(def-plan-fn configure-dhcp-server
  "Perform configuration needed to have a working DHCP server."
  []
  (update-dhcp-config)
  (install-dnsmasq-upstart-job))

(def-plan-fn configure-image-server
  "Perform configuration needed to have a working KVM image server."
  []
  ;; TODO: outstanding
  )

(def-plan-fn create-image
  "Create a KVM image according to a given spec"
  []
  )

(def-plan-fn create-guest-vm
  "TODO: implement"
  []
  )
