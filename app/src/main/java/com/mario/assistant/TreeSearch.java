package com.mario.assistant;

import java.util.ArrayList;
import java.util.List;

final class TreeSearch {
    interface Access<T> { boolean matches(T node, String label); List<T> children(T node); }
    static <T> List<T> find(T root, String label, Access<T> access, int limit) {
        List<T> found = new ArrayList<>();
        if (root == null || limit <= 0) return found;
        List<T> queue = new ArrayList<>();
        queue.add(root);
        for (int i = 0; i < queue.size(); i++) {
            T node = queue.get(i);
            if (access.matches(node, label)) found.add(node);
            for (T child : access.children(node)) {
                if (child != null && queue.size() < limit) queue.add(child);
            }
        }
        return found;
    }
}
