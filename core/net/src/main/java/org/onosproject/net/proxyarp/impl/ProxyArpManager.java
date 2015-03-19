/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.net.proxyarp.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.Link;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.host.HostService;
import org.onosproject.net.host.InterfaceIpAddress;
import org.onosproject.net.host.PortAddresses;
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.link.LinkListener;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.proxyarp.ProxyArpService;
import org.slf4j.Logger;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

@Component(immediate = true)
@Service
public class ProxyArpManager implements ProxyArpService {

    private final Logger log = getLogger(getClass());

    private static final int FLOW_RULE_PRIORITY = 40000;

    private static final String MAC_ADDR_NULL = "Mac address cannot be null.";
    private static final String REQUEST_NULL = "Arp request cannot be null.";
    private static final String REQUEST_NOT_ARP = "Ethernet frame does not contain ARP request.";
    private static final String NOT_ARP_REQUEST = "ARP is not a request.";
    private static final String NOT_ARP_REPLY = "ARP is not a reply.";

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LinkService linkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    private final Multimap<Device, PortNumber> internalPorts =
            HashMultimap.<Device, PortNumber>create();

    private final Multimap<Device, PortNumber> externalPorts =
            HashMultimap.<Device, PortNumber>create();

    private ApplicationId appId;

    /**
     * Listens to both device service and link service to determine
     * whether a port is internal or external.
     */
    @Activate
    public void activate() {
        appId =
            coreService.registerApplication("org.onosproject.net.proxyarp");

        deviceService.addListener(new InternalDeviceListener());
        linkService.addListener(new InternalLinkListener());
        determinePortLocations();
        pushRules();

        log.info("Started");
    }


    @Deactivate
    public void deactivate() {
        log.info("Stopped");
    }

    @Override
    public boolean known(Ip4Address addr) {
        checkNotNull(addr, MAC_ADDR_NULL);
        //为什呢ipv4地址为空的声明是mac地址不能为空
        Set<Host> hosts = hostService.getHostsByIp(addr);//VARP?
        return !hosts.isEmpty();
    }

    @Override
    public void reply(Ethernet eth, ConnectPoint inPort) {
        checkNotNull(eth, REQUEST_NULL);
        checkArgument(eth.getEtherType() == Ethernet.TYPE_ARP,
                REQUEST_NOT_ARP);
        ARP arp = (ARP) eth.getPayload();
        checkArgument(arp.getOpCode() == ARP.OP_REQUEST, NOT_ARP_REQUEST);
        checkNotNull(inPort);

        // If the request came from outside the network, only reply if it was
        // for one of our external addresses.
        //如果连接来自外部端口,那么只有当它的目的地址为本路由的外部地址才回应
        if (isOutsidePort(inPort)) {
            //如果是已知端口,outside-facing port
            Ip4Address target =
                Ip4Address.valueOf(arp.getTargetProtocolAddress());
            //
            Set<PortAddresses> addressSet =
                hostService.getAddressBindingsForPort(inPort);
            //先记录下inPort对应的IP地址,至于为什么返回的是一个Set我认识为是因为考虑到多个interface的缘故

            for (PortAddresses addresses : addressSet) {
                //对于绑定在inPort的addresses 做遍历
                for (InterfaceIpAddress ia : addresses.ipAddresses()) {
                    //PortAddress 里面有定义了一个interface的Set,为什么那个方法返回的是Set?
                    if (ia.ipAddress().equals(target)) {
                        Ethernet arpReply =
                            buildArpReply(target, addresses.mac(), eth);
                        sendTo(arpReply, inPort);
                    }
                }
            }
            return;
        } else {
            // If the source address matches one of our external addresses
            // it could be a request from an internal host to an external
            // address. Forward it over to the correct ports.

            Ip4Address source =
                Ip4Address.valueOf(arp.getSenderProtocolAddress());
            Set<PortAddresses> sourceAddresses = findPortsInSubnet(source);
            boolean matched = false;
            for (PortAddresses pa : sourceAddresses) {
                for (InterfaceIpAddress ia : pa.ipAddresses()) {
                    if (ia.ipAddress().equals(source)) {
                        matched = true;
                        sendTo(eth, pa.connectPoint());
                        //这个eth为什么就直接转发过去了,而不是使用buildarpreply功能
                    }
                }
            }

            if (matched) {
                return;
            }
        }

        // Continue with normal proxy ARP case

        VlanId vlan = VlanId.vlanId(eth.getVlanID());
        Set<Host> hosts = hostService.getHostsByIp(
                        Ip4Address.valueOf(arp.getTargetProtocolAddress()));

        Host dst = null;
        Host src = hostService.getHost(HostId.hostId(eth.getSourceMAC(),
                VlanId.vlanId(eth.getVlanID())));
        //hostId就是mac地址

        for (Host host : hosts) {
            if (host.vlan().equals(vlan)) {
                dst = host;
                break;
            }
        }

        if (src == null || dst == null) {
            flood(eth);
            return;
        }

        //
        // TODO find the correct IP address.
        // Right now we use the first IPv4 address that is found.
        //
        for (IpAddress ipAddress : dst.ipAddresses()) {
            Ip4Address ip4Address = ipAddress.getIp4Address();
            if (ip4Address != null) {
                Ethernet arpReply = buildArpReply(ip4Address, dst.mac(), eth);
                // TODO: check send status with host service.
                sendTo(arpReply, src.location());
                break;
            }
        }
    }

    /**
     * Outputs the given packet out the given port.
     *
     * @param packet the packet to send
     * @param outPort the port to send it out
     */
    private void sendTo(Ethernet packet, ConnectPoint outPort) {
        if (internalPorts.containsEntry(
                deviceService.getDevice(outPort.deviceId()), outPort.port())) {
            // Sanity check to make sure we don't send the packet out an
            // internal port and create a loop (could happen due to
            // misconfiguration).
            //防止将报文从内部端口发出去,可能会造成循环
            return;
        }

        TrafficTreatment.Builder builder = DefaultTrafficTreatment.builder();
        builder.setOutput(outPort.port());
        packetService.emit(new DefaultOutboundPacket(outPort.deviceId(),
                builder.build(), ByteBuffer.wrap(packet.serialize())));
        //这里为什么包起来ByteBuffer,不应该是从buffer中读取buffer并且转发吗
        //反正就是打包发出去了
    }

    /**
     * Finds ports with an address in the subnet of the target address.
     *
     * @param target the target address to find a matching port for
     * @return a set of PortAddresses describing ports in the subnet
     */
    private Set<PortAddresses> findPortsInSubnet(Ip4Address target) {
        Set<PortAddresses> result = new HashSet<PortAddresses>();
        for (PortAddresses addresses : hostService.getAddressBindings()) {
            for (InterfaceIpAddress ia : addresses.ipAddresses()) {
                if (ia.subnetAddress().contains(target)) {
                    result.add(addresses);
                }
            }
        }
        return result;
    }

    /**
     * Returns whether the given port is an outside-facing port with an IP
     * address configured.
     *
     * @param port the port to check
     * @return true if the port is an outside-facing port, otherwise false
     */
    private boolean isOutsidePort(ConnectPoint port) {
        //
        // TODO: Is this sufficient to identify outside-facing ports: just
        // having IP addresses on a port?
        //
        return !hostService.getAddressBindingsForPort(port).isEmpty();
        //有绑定IP就说明是outsideport
    }

    @Override
    public void forward(Ethernet eth) {
        //forward 和sendTO方法有什么区别?
        checkNotNull(eth, REQUEST_NULL);
        checkArgument(eth.getEtherType() == Ethernet.TYPE_ARP,
                REQUEST_NOT_ARP);
        ARP arp = (ARP) eth.getPayload();
        checkArgument(arp.getOpCode() == ARP.OP_REPLY, NOT_ARP_REPLY);
        //由此可以看出forward必须是转发ARP 回复报文

        Host h = hostService.getHost(HostId.hostId(eth.getDestinationMAC(),
                VlanId.vlanId(eth.getVlanID())));

        if (h == null) {
            flood(eth);
        } else {
            TrafficTreatment.Builder builder = DefaultTrafficTreatment.builder();
            builder.setOutput(h.location().port());
            packetService.emit(new DefaultOutboundPacket(h.location().deviceId(),
                    builder.build(), ByteBuffer.wrap(eth.serialize())));
        }

    }

    @Override
    public boolean handleArp(PacketContext context) {
        //这里的packetcontext到底指的什么,packetIn消息?
        //这个方法才是整个类的入口
        InboundPacket pkt = context.inPacket();
        Ethernet ethPkt = pkt.parsed();
        if (ethPkt != null && ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
            ARP arp = (ARP) ethPkt.getPayload();
            if (arp.getOpCode() == ARP.OP_REPLY) {
                forward(ethPkt);
            } else if (arp.getOpCode() == ARP.OP_REQUEST) {
                reply(ethPkt, context.inPacket().receivedFrom());//这个方法返回inport
            }
            context.block();
            return true;
        }
        return false;
    }

    /**
     * Flood the arp request at all edges in the network.
     * @param request the arp request.
     */
    private void flood(Ethernet request) {
        TrafficTreatment.Builder builder = null;
        ByteBuffer buf = ByteBuffer.wrap(request.serialize());

        synchronized (externalPorts) {
            for (Entry<Device, PortNumber> entry : externalPorts.entries()) {
                ConnectPoint cp = new ConnectPoint(entry.getKey().id(), entry.getValue());
                if (isOutsidePort(cp)) {
                    continue;
                }

                builder = DefaultTrafficTreatment.builder();
                builder.setOutput(entry.getValue());
                packetService.emit(new DefaultOutboundPacket(entry.getKey().id(),
                        builder.build(), buf));
                //如果flood也是这样直接调用emit方法,那么本来应该在sendto中下发的流表去哪了?
                //是通过setUpConnectivity方法下发流表的,那为什么已经处理的arp表项不顺便下发一个arp流表?
                //这个问题等我等会测试一下,看proxyarp处理过的之后有没有
            }
        }
    }

    /**
     * Determines the location of all known ports in the system.
     */
    private void determinePortLocations() {
        Iterable<Device> devices = deviceService.getDevices();
        Iterable<Link> links = null;
        List<PortNumber> ports = null;
        for (Device d : devices) {
            ports = buildPortNumberList(deviceService.getPorts(d.id()));
            links = linkService.getLinks();
            for (Link l : links) {
                // for each link, mark the concerned ports as internal
                // and the remaining ports are therefore external.
                if (l.src().deviceId().equals(d.id())
                        && ports.contains(l.src().port())) {
                    ports.remove(l.src().port());
                    internalPorts.put(d, l.src().port());
                }
                if (l.dst().deviceId().equals(d.id())
                        && ports.contains(l.dst().port())) {
                    ports.remove(l.dst().port());
                    internalPorts.put(d, l.dst().port());
                }
            }
            synchronized (externalPorts) {
                externalPorts.putAll(d, ports);
            }
        }

    }

    private List<PortNumber> buildPortNumberList(List<Port> ports) {
        List<PortNumber> portNumbers = Lists.newLinkedList();
        for (Port p : ports) {
            portNumbers.add(p.number());
        }
        return portNumbers;
    }

    /**
     * Builds an ARP reply based on a request.
     *
     * @param srcIp the IP address to use as the reply source
     * @param srcMac the MAC address to use as the reply source
     * @param request the ARP request we got
     * @return an Ethernet frame containing the ARP reply
     */
    private Ethernet buildArpReply(Ip4Address srcIp, MacAddress srcMac,
            Ethernet request) {

        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(request.getSourceMAC());
        eth.setSourceMACAddress(srcMac);
        eth.setEtherType(Ethernet.TYPE_ARP);
        eth.setVlanID(request.getVlanID());

        ARP arp = new ARP();
        arp.setOpCode(ARP.OP_REPLY);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);

        arp.setProtocolAddressLength((byte) Ip4Address.BYTE_LENGTH);
        arp.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH);
        arp.setSenderHardwareAddress(srcMac.toBytes());
        arp.setTargetHardwareAddress(request.getSourceMACAddress());

        arp.setTargetProtocolAddress(((ARP) request.getPayload())
                .getSenderProtocolAddress());
        arp.setSenderProtocolAddress(srcIp.toInt());
        eth.setPayload(arp);
        return eth;
    }

    /**
     * Pushes flow rules to all devices.
     */
    private void pushRules() {
        for (Device device : deviceService.getDevices()) {
            pushRules(device);
        }
    }

    /**
     * Pushes flow rules to the device to receive control packets that need
     * to be processed.
     *
     * @param device the device to push the rules to
     */
    private synchronized void pushRules(Device device) {
        TrafficSelector.Builder sbuilder = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder tbuilder = DefaultTrafficTreatment.builder();

        // Get all ARP packets
        sbuilder.matchEthType(Ethernet.TYPE_ARP);
        tbuilder.setOutput(PortNumber.CONTROLLER);
        FlowRule flowArp =
            new DefaultFlowRule(device.id(),
                                sbuilder.build(), tbuilder.build(),
                                FLOW_RULE_PRIORITY, appId, 0, true);

        flowRuleService.applyFlowRules(flowArp);
    }

    public class InternalLinkListener implements LinkListener {

        @Override
        public void event(LinkEvent event) {
            Link link = event.subject();
            Device src = deviceService.getDevice(link.src().deviceId());
            Device dst = deviceService.getDevice(link.dst().deviceId());
            switch (event.type()) {
                case LINK_ADDED:
                    synchronized (externalPorts) {
                        externalPorts.remove(src, link.src().port());
                        externalPorts.remove(dst, link.dst().port());
                        internalPorts.put(src, link.src().port());
                        internalPorts.put(dst, link.dst().port());
                    }

                    break;
                case LINK_REMOVED:
                    synchronized (externalPorts) {
                        externalPorts.put(src, link.src().port());
                        externalPorts.put(dst, link.dst().port());
                        internalPorts.remove(src, link.src().port());
                        internalPorts.remove(dst, link.dst().port());
                    }

                    break;
                case LINK_UPDATED:
                    // don't care about links being updated.
                    break;
                default:
                    break;
            }

        }

    }

    public class InternalDeviceListener implements DeviceListener {

        @Override
        public void event(DeviceEvent event) {
            Device device = event.subject();
            switch (event.type()) {
                case DEVICE_ADDED:
                    pushRules(device);
                    break;
                case DEVICE_AVAILABILITY_CHANGED:
                case DEVICE_SUSPENDED:
                case DEVICE_UPDATED:
                 // nothing to do in these cases; handled when links get reported
                    break;
                case DEVICE_REMOVED:
                    synchronized (externalPorts) {
                        externalPorts.removeAll(device);
                        internalPorts.removeAll(device);
                    }
                    break;
                case PORT_ADDED:
                case PORT_UPDATED:
                    synchronized (externalPorts) {
                        if (event.port().isEnabled()) {
                            externalPorts.put(device, event.port().number());
                            internalPorts.remove(device, event.port().number());
                        }
                    }
                    break;
                case PORT_REMOVED:
                    synchronized (externalPorts) {
                        externalPorts.remove(device, event.port().number());
                        internalPorts.remove(device, event.port().number());
                    }
                    break;
                default:
                    break;

            }

        }

    }

}
