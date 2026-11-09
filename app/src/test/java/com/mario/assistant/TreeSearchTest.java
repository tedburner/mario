package com.mario.assistant;

import com.mario.assistant.infrastructure.a11y.TreeSearch;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TreeSearchTest {
    static class Node {
        final String label; final List<Node> children;
        Node(String label, Node... children) { this.label = label; this.children = Arrays.asList(children); }
    }
    static final TreeSearch.Access<Node> ACCESS = new TreeSearch.Access<Node>() {
        public boolean matches(Node n, String label) { return label.equals(n.label); }
        public List<Node> children(Node n) { return n.children; }
    };
    @Test public void findsDescendantsWhenPlatformTextSearchDoesNot() {
        Node button = new Node("立即领取");
        Node root = new Node("root", new Node("row", button));
        assertEquals(Collections.singletonList(button), TreeSearch.find(root, "立即领取", ACCESS, 100));
    }
    @Test public void honoursTraversalLimit() {
        Node root = new Node("root", new Node("row", new Node("立即领取")));
        assertTrue(TreeSearch.find(root, "立即领取", ACCESS, 2).isEmpty());
    }
}
