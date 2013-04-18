package com.facebook.presto.execution;

import com.facebook.presto.spi.HostAddress;
import com.facebook.presto.metadata.Node;
import com.facebook.presto.metadata.NodeManager;
import com.facebook.presto.spi.Split;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.SetMultimap;
import com.google.common.net.InetAddresses;
import org.weakref.jmx.Managed;

import javax.inject.Inject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class NodeScheduler
{
    private final NodeManager nodeManager;
    private final AtomicLong scheduleLocal = new AtomicLong();
    private final AtomicLong scheduleRack = new AtomicLong();
    private final AtomicLong scheduleRandom = new AtomicLong();

    @Inject
    public NodeScheduler(NodeManager nodeManager)
    {
        this.nodeManager = nodeManager;
    }

    @Managed
    public long getScheduleLocal()
    {
        return scheduleLocal.get();
    }

    @Managed
    public long getScheduleRack()
    {
        return scheduleRack.get();
    }

    @Managed
    public long getScheduleRandom()
    {
        return scheduleRandom.get();
    }

    @Managed
    public void reset()
    {
        scheduleLocal.set(0);
        scheduleRack.set(0);
        scheduleRandom.set(0);
    }

    public NodeSelector createNodeSelector(final String dataSourceName, final Comparator<Node> nodeComparator)
    {
        // this supplier is thread-safe. TODO: this logic should probably move to the scheduler since the choice of which node to run in should be
        // done as close to when the the split is about to be scheduled
        final Supplier<NodeMap> nodeMap = Suppliers.memoizeWithExpiration(new Supplier<NodeMap>()
        {
            @Override
            public NodeMap get()
            {
                ImmutableSetMultimap.Builder<HostAddress, Node> byHostAndPort = ImmutableSetMultimap.builder();
                ImmutableSetMultimap.Builder<InetAddress, Node> byHost = ImmutableSetMultimap.builder();
                ImmutableSetMultimap.Builder<Rack, Node> byRack = ImmutableSetMultimap.builder();

                Set<Node> nodes;
                if (dataSourceName != null) {
                    nodes = nodeManager.getActiveDatasourceNodes(dataSourceName);
                } else {
                    nodes = nodeManager.getActiveNodes();
                }

                for (Node node : nodes) {
                    try {
                        byHostAndPort.put(node.getHostAndPort(), node);

                        InetAddress host = InetAddress.getByName(node.getHttpUri().getHost());
                        byHost.put(host, node);

                        byRack.put(Rack.of(host), node);
                    }
                    catch (UnknownHostException e) {
                        // ignore
                    }
                }

                return new NodeMap(byHostAndPort.build(), byHost.build(), byRack.build());
            }
        }, 5, TimeUnit.SECONDS);

        return new NodeSelector(nodeComparator, nodeMap);
    }

    public class NodeSelector
    {
        private final Comparator<Node> nodeComparator;
        private final Supplier<NodeMap> nodeMap;

        public NodeSelector(Comparator<Node> nodeComparator, Supplier<NodeMap> nodeMap)
        {
            this.nodeComparator = nodeComparator;
            this.nodeMap = nodeMap;
        }

        public Node selectRandomNode()
        {
            // create a single partition on a random node for this fragment
            ArrayList<Node> nodes = new ArrayList<>(nodeMap.get().getNodesByHostAndPort().values());
            Preconditions.checkState(!nodes.isEmpty(), "Cluster does not have any active nodes");
            Collections.shuffle(nodes, ThreadLocalRandom.current());
            return nodes.get(0);
        }

        public Node selectNode(Split split)
        {
            // select 10 acceptable nodes
            List<Node> nodes = selectNodes(nodeMap.get(), split, 10);

            if (nodes.isEmpty()) {
                System.out.println("here");
            }
            // select the node with the smallest number of assignments
            Node chosen = Ordering.from(nodeComparator).min(nodes);
            return chosen;
        }

        private List<Node> selectNodes(NodeMap nodeMap, Split split, int minCount)
        {
            Set<Node> chosen = new LinkedHashSet<>(minCount);

            for (HostAddress hint : split.getAddresses()) {
                for (Node node : nodeMap.getNodesByHostAndPort().get(hint)) {
                    if (chosen.add(node)) {
                        scheduleLocal.incrementAndGet();
                    }
                }

                if (split.isRemotelyAccessible()) {
                    InetAddress address = hint.toInetAddress();
                    for (Node node : nodeMap.getNodesByHost().get(address)) {
                        if (chosen.add(node)) {
                            scheduleLocal.incrementAndGet();
                        }
                    }

                    for (Node node : nodeMap.getNodesByRack().get(Rack.of(address))) {
                        if (chosen.add(node)) {
                            scheduleRack.incrementAndGet();
                        }
                    }
                }
            }

            // add some random nodes if below the minimum count
            if (split.isRemotelyAccessible()) {
                if (chosen.size() < minCount) {
                    for (Node node : lazyShuffle(nodeMap.getNodesByHost().values())) {
                        if (chosen.add(node)) {
                            scheduleRandom.incrementAndGet();
                        }

                        if (chosen.size() == minCount) {
                            break;
                        }
                    }
                }
            }

            return ImmutableList.copyOf(chosen);
        }
    }

    private static <T> Iterable<T> lazyShuffle(final Iterable<T> iterable)
    {
        return new Iterable<T>()
        {
            @Override
            public Iterator<T> iterator()
            {
                return new AbstractIterator<T>()
                {
                    List<T> list = Lists.newArrayList(iterable);
                    int limit = list.size();

                    @Override
                    protected T computeNext()
                    {
                        if (limit == 0) {
                            return endOfData();
                        }

                        int position = ThreadLocalRandom.current().nextInt(limit);

                        T result = list.get(position);
                        list.set(position, list.get(limit - 1));
                        limit--;

                        return result;
                    }
                };
            }
        };
    }

    private static class NodeMap
    {
        private final SetMultimap<HostAddress, Node> nodesByHostAndPort;
        private final SetMultimap<InetAddress, Node> nodesByHost;
        private final SetMultimap<Rack, Node> nodesByRack;

        public NodeMap(SetMultimap<HostAddress, Node> nodesByHostAndPort, SetMultimap<InetAddress, Node> nodesByHost, SetMultimap<Rack, Node> nodesByRack)
        {
            this.nodesByHostAndPort = nodesByHostAndPort;
            this.nodesByHost = nodesByHost;
            this.nodesByRack = nodesByRack;
        }

        private SetMultimap<HostAddress, Node> getNodesByHostAndPort()
        {
            return nodesByHostAndPort;
        }

        public SetMultimap<InetAddress, Node> getNodesByHost()
        {
            return nodesByHost;
        }

        public SetMultimap<Rack, Node> getNodesByRack()
        {
            return nodesByRack;
        }
    }

    private static class Rack
    {
        private int id;

        public static Rack of(InetAddress address)
        {
            // TODO: this needs to be pluggable
            int id = InetAddresses.coerceToInteger(address) & 0xFF_FF_FF_00;
            return new Rack(id);
        }

        private Rack(int id)
        {
            this.id = id;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            Rack rack = (Rack) o;

            if (id != rack.id) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode()
        {
            return id;
        }
    }
}
