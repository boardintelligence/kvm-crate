#!/bin/bash

# get custom debs we need
mkdir -p resources/custom-debs
cd resources/custom-debs
wget http://vagrant.vannavolga.info/boxes/libvirt0_1.0.0-0ubuntu4_amd64.deb
wget http://vagrant.vannavolga.info/boxes/libvirt0-dbg_1.0.0-0ubuntu4_amd64.deb
wget http://vagrant.vannavolga.info/boxes/libvirt-bin_1.0.0-0ubuntu4_amd64.deb
wget http://vagrant.vannavolga.info/boxes/libvirt-dev_1.0.0-0ubuntu4_amd64.deb
wget http://vagrant.vannavolga.info/boxes/libvirt-doc_1.0.0-0ubuntu4_all.deb
