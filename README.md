# kvm-crate

A Pallet crate to work with KVM servers and KVM guests.

There is no KVM backend for jclouds/pallet and this is a first attempt
at "manually" supporting KVM. By manually I mean that all operations
are done via the node-list and lift (converge is never used).

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

Step 2 and 3 will be described in more detail shortly, let's first see how
the call looks (assuming you are use'ing the kvm-create.api namespace):
    (with-config [hosts-config {}]
      (configure-kvm-server "host.to.configure")

The second argument to *with-config* is a map that will be passed as the :environment
argument to any subsequent lift operation that takes place under the covers (and is
hence available to any of your own pallet plan functions.

The format of the hosts-config argument that kvm-crate looks for is this (it's ok to
add additional content):
    {"host.to.configure" {:host-type :kvm-server
                          :group-spec kvm-create.specs/kvm-server-g
                          :ip "1.2.3.4"
                          :admin-user {:username "root"
                                       :ssh-public-key-path  (utils/resource-path "ssh-keys/kvm-keys/kvm-id_rsa.pub")
                                       :ssh-private-key-path (utils/resource-path "ssh-keys/kvm-keys/kvm-id_rsa")
                                       :passphrase "foobar"}}}

(The function *utils/resource-path* is from the namespace pallet.utils and
is handy for referring to paths on the local machine)

As for step 3 the custom debs are too big to distribute with kvm-create
and are instead downloaded onto the machine pallet is run from, then
transferred over the KVM server target for install. An example script
of how and where to place the files can be found in the provided
*download-custom-debs.sh* script.

You can use *configure-kvm-server* in your own functions to create
more complete functions that do more than just the KVM server
configuration. For example this is how we setup newly ordered Hetzner
servers as KVM servers:
    (defn configure-hetzner-kvm-server
      "Setup a KVM server in Hetzner data-center"
      [hostname]
      (kvm-api/with-config [hosts-config/config {}]
        (hetzner-api/hetzner-initial-setup hostname)
        (println (format "Sleep 3mins while we wait for KVM server %s to reboot.." hostname))
        (Thread/sleep (* 3 60 1000)) ;; wait for the host to reboot
        (kvm-api/configure-kvm-server hostname)))

(the *hetzner-initial-setup* function performs actions needed to
for a fresh Hetzner machine)

## Creating a KVM guest VM

FIXME

## Connecting the openvswitches of several KVM servers

To create one big VLAN where KVM guests on seveal KVM servers can
communicate with each other we use GRE+IPSec to connect the
openvswitches of the KVM servers.

FIXME

## License

Copyright © 2013 Board Intelligence

Distributed under the MIT License, see http://boardintelligence.mit-license.org for details.
