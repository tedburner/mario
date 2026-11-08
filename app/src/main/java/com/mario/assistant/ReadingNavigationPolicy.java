package com.mario.assistant;

final class ReadingNavigationPolicy {
    static boolean mayOpen(String pkg, boolean accountAnchor, boolean activityAnchor,
                           int count, boolean eligible) {
        return "com.qidian.QDReader".equals(pkg) && accountAnchor && activityAnchor
                && count == 1 && eligible;
    }
    static boolean mayTapMyTab(int tabCount, boolean inBottomZone) {
        return tabCount == 1 && inBottomZone;
    }
}
