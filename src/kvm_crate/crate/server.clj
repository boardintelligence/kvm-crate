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
                               "openvswitch-controller" "openvswitch-brcompat"
                               "openvswitch-switch" "openvswitch-datapath-source"
                               "openvswitch-ipsec" "libnetcf1" "libxen-dev"]))

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

(def-plan-fn install-custom-libvirt-packages
  "Install needed libvirt debs for KVM server using openvswitch."
  []
  [custom-debs (m-result ["libvirt0_1.0.0-0ubuntu4_amd64.deb"
                          "libvirt0-dbg_1.0.0-0ubuntu4_amd64.deb"
                          "libvirt-bin_1.0.0-0ubuntu4_amd64.deb"
                          "libvirt-dev_1.0.0-0ubuntu4_amd64.deb"
                          "libvirt-doc_1.0.0-0ubuntu4_all.deb"])]
  (map install-custom-deb custom-debs))

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
       (virsh net-autostart --disable default)))
   (service libvirt-bin stop)
   (service qemu-kvm stop)))

(def-plan-fn remove-ebtables
  "Remove ebtables, not needed."
  []
  (actions/exec-checked-script
   "Remove ebtables package, not needed"
   (aptitude --assume-yes purge ebtables)))

(def-plan-fn auto-install-ovs-modules
  "Run module-assistant to make sure the ovs modules are properly installed."
  []
  (actions/exec-checked-script
   "Use module-assistant to install ovs modules"
   (module-assistant --text-mode --non-inter auto-install openvswitch-datapath)))

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

(def-plan-fn perform-ovs-vsctl-steps
  "Perform ovs-vsctl steps to setup OVS bridge and internal intefaces."
  []
  [node-hostname crate/target-name
   host-config (env/get-environment [:host-config node-hostname])
   ovs-vsctl-steps (m-result (:ovs-vsctl-steps host-config))]
  (actions/exec-checked-script
   "Create, add and configure OVS bridge + restart libvirt and qemu-kvm"
   ;; Note: we're using at here in order to avoid a failure when the
   ;;       connection to host goes down.
   (pipe (echo ~(format "\"/etc/init.d/networking restart;%s;service libvirt-bin start;service qemu-kvm start\""
                        ovs-vsctl-steps))
         (at -M "now + 1 minute"))))

(def-plan-fn configure-openvswitch
  "Configure openvswtich for KVM server."
  []
  (install-etc-interfaces)
  (install-failsafe-conf)
  (remove-libvirt-network)
  (remove-ebtables)
  (auto-install-ovs-modules)
  (perform-ovs-vsctl-steps))

(def-plan-fn configure-server
  "Install packages for KVM server and configure networking."
  []
  (install-standard-packages)
  (install-custom-libvirt-packages)
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

(defn is-dhcp-ip?
  "Is IP to be assinged by DHCP?"
  [config]
  (and (not (nil? (:ip config)))
       (not (nil? (:mac config)))))

(def-plan-fn update-dhcp-hosts-file
  []
  [node-hostname crate/target-name
   dhcp-hosts-content (env/get-environment [:dhcp-hosts-content])
   opts-file (env/get-environment [:host-config node-hostname :dnsmasq-optsfile])]

  (actions/remote-file "/etc/ovs-net-dnsmasq.opts"
                       :local-file opts-file)
  (actions/remote-file "/etc/ovs-net-dnsmasq.hosts"
                       :content dhcp-hosts-content
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
                       :values {:interface interface}))
