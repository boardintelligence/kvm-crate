# kvm-crate

A Pallet crate to work with KVM servers and KVM guests.

There is no KVM backend for jclouds/pallet and this is a first attempt
at "manually" supporting KVM. By manually I mean that all operations
are done via the node-list provider and lift (converge is never used).

Hence you define your servers and guests in a config map (the format is
described further down), then perform specific phases on the KVM server(s) to:
* Setup an already existing host as a KVM server
* Create a KVM guest VM on a given KVM server
* Create one big VLAN spanning several KVM servers

After that you can of course perform operations on the KVM guest VMs
directly without going via the KVM server.

For the moment kvm-crate assumes KVM servers are Ubuntu 12.04 LTS hosts. This
restriction can loosened in the future if others provide variations that
works on other distributions and versions.

kvm-crate utilizes the following for setting up VMs and networking:
* python-vm-builder to create VM images from scratch (if not using base images)
* libvirt for managing guests
* openvswitch for networking (and GRE+IPSec for connecting OVS on several KVM servers)

As it stands the libvirt shipped with Ubuntu 12.04 does not come with
support for openvswitch. Hence I've prepared some custom libvirt 1.0
packages that are used instead (prepared from the Ubuntu raring sources).

## Configuring a KVM server

First all make sure the following things hold true:
1. Your intended KVM server host exists and runs Ubuntu 12.04 LTS
2. The host is included in your hosts config map
3. You have downloaded the custom libvirt deps to "custom-debs/" somewhere on the classpath

As for step 3 the custom debs are too big to distribute with kvm-create
and are instead downloaded onto the machine pallet is run from, then
transferred over the KVM server target for install. An example script
of how and where to place the files can be found in the provided
*download-custom-debs.sh* script.

Step 2 will be described in more detail shortly, let's first see how
the call looks (assuming you are use'ing the kvm-create.api namespace):

    (with-config [hosts-config {}]
      (configure-kvm-server "host.to.configure")

The second argument to *with-config* is a map that will be passed as the :environment
argument to any subsequent lift operation that takes place under the covers (and is
hence available to any of your own pallet plan functions).

The format of the hosts-config argument that kvm-crate looks for is this (it's ok to
add additional content):

    {"host.to.configure" {:host-type :kvm-server
                          :group-spec kvm-create.specs/kvm-server-g
                          :ip "1.2.3.4"
                          :admin-user {:username "root"
                                       :ssh-public-key-path  (utils/resource-path "ssh-keys/kvm-keys/kvm-id_rsa.pub")
                                       :ssh-private-key-path (utils/resource-path "ssh-keys/kvm-keys/kvm-id_rsa")
                                       :passphrase "foobar"}
                          :interfaces-file "ovs/interfaces"
                          :interface-config {:host0-ip4-ip        "public.iface.ip.address"
                                             :host0-ip4-broadcast "public.iface.bcast"
                                             :host0-ip4-netmask   "public.iface.netmask"
                                             :host0-ip4-gateway   "public.iface.gw"
                                             :host0-ip4-net       "public.iface.net"
                                             :host0-ip6-ip        "public.iface.ip6.address"
                                             :host0-ip6-netmask   "public.iface.ip6.netmask"
                                             :host0-ip6-gateway   "public.iface.ip6.gw"
                                             :host1-ip4-ip        "private.iface.address"
                                             :host1-ip4-broadcast "private.iface.bcast"
                                             :host1-ip4-netmask   "private.iface.netmask"
                                             :host1-ip4-net       "private.iface.net"}
                          :ovs-vsctl-steps "ovs-vsctl add-br ovsbr0;
                            ovs-vsctl add-br ovsbr1
                            ovs-vsctl add-port ovsbr0 eth0;
                            ovs-vsctl add-port ovsbr0 ovshost0 -- set interface ovshost0 type=internal;
                            ovs-vsctl add-port ovsbr1 ovshost1 -- set interface ovshost1 type=internal"}}

(The function *utils/resource-path* is from the namespace pallet.utils and
is handy for referring to paths on the local machine)

I've left the configuration of the OpenVSwitch network pretty free form. Hence
the parts in *:interface-config* and *:ovs-vsctl-steps* are freeform and needs
to be compatible with the content of the file given in the files references
by *:interfaces-file*. You can find an example in the
*kvm-crate/resources/ovs/interfaces* file that is compatible with the config
example above. The example assumes one public interface on eth0 that is added to
the OVS, we create 2 internal interfaces ovshost0 and ovshost1. The ovshost0
inteface gets the same config as eth0 would have had before we made it part of
the OVS setup, and ovshost1 is an interace we put on the private network we'll
set up for the KVM guest VMs of the host. We will use this interface to
do things like serve DHCP and DNS on the private network.

The *ovs-vsctl-steps* is a freeform set of instructions that are supposed
to properly configure OVS taking into account how your /etc/network/interfaces
and later private network for the KVM guest VMs are setup. The example
above is compatible with the setup descrived in the previous paragraph.

The *configure-kvm-server* function in the *api* namespace can be used
as a convenience method to perform the KVM server setup. Note it will
make the network changes made on the KVM server come into effect 1 minute
after it has run (via the at command). This is because the network changes
will cause our connection to drop and signal an error. To avoid this we delay
execution until after we have disconnected from the host. **NOTE: if you
mess up the ovs-vsctl steps it's quite possible to lock yourself out of
the remote machine so please take extra special care making sure it
will run cleanly since you may not get a 2nd shot!**.

You can use the *configure-kvm-server* function in your own functions to
create more complete functions that do more than just the KVM server
configuration. as its last step since
as part of For example this is how we setup newly ordered Hetzner
servers as KVM servers:

    (defn configure-hetzner-kvm-server
      "Setup a KVM server in Hetzner data-center"
      [hostname]
      (helpers/with-nodelist-config [hosts-config/config {}]
        (println (format "Initial setup for Hetzner host %s.." hostname))
        (hetzner-api/hetzner-initial-setup hostname)
        (println (format "Sleep 3mins while we wait for KVM server %s to reboot.." hostname))
        (Thread/sleep (* 3 60 1000)) ;; wait for the host to reboot
        (println (format "Configuring KVM server for %s.." hostname))
        (kvm-api/configure-kvm-server hostname)
        (println (format "Finished configuring KVM server %s. Note: it will perform the network changes in 1min!" hostname))))

(the *hetzner-initial-setup* function performs actions needed to
for a fresh Hetzner machine)

A tip when working with the host config maps is to DRY up your code by
definining parameterized functions that produce maps of certain types
representing certain types of hosts. This way you can also compose
several such functions via *merge*. An example is that you could have
one function producing the config map required by the Hetzner crate,
and another for the kvm crate. Use both of these + merge to create
a config map for a host at Hetzner that is also a KVM server.

## Creating a KVM guest VM

NOT IMPLEMENTED YET

## Connecting the OVS's of several KVM servers

To create one big VLAN where KVM guests on seveal KVM servers can
communicate with each other we use GRE+IPSec to connect the
openvswitches of the KVM servers.

The *connect-kvm-servers* function in the *api* namespace will
connect your KVM servers as specified by the gre connections in
the config. The host config map info related to gre connections
take the following form:

    {"host2.to.configure" {:gre-connections
                           [{:bridge "ovsbr1"
                             :iface "gre0"
                             :remote-ip "1.2.3.4"
                             :psk "my secret key"}]}
     "host2.to.configure" {:gre-connections
                           [{:bridge "ovsbr1"
                             :iface "gre0"
                             :remote-ip "4.3.2.1"
                             :psk "my secret key"}]}}

In this example we only have two hosts and there's one GRE connection
setup between the two. If we had more we'd just specify pairs of
matching GRE connections for the appropriate hosts (note the vector
of hashes, just add more hashes to have more connections for a given
host).

## License

Copyright © 2013 Board Intelligence

Distributed under the MIT License, see
[http://boardintelligence.mit-license.org](http://boardintelligence.mit-license.org)
for details.
