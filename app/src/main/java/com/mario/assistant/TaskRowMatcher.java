package com.mario.assistant;

import java.util.List;

final class TaskRowMatcher {
    static final class Row {
        final String title, action;
        final boolean visible;
        Row(String title, String action, boolean visible) {
            this.title = title; this.action = action; this.visible = visible;
        }
    }
    static boolean supports(String title) {
        return "逛支付宝会员中心".equals(title) || "逛一逛芝麻攒粒攻略".equals(title);
    }
    static int findClaim(List<Row> rows) { return findAction(rows, "立即领取"); }
    static int find(List<Row> rows) { return findAction(rows, "逛一逛"); }
    private static int findAction(List<Row> rows, String action) {
        int match = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row != null && row.visible && supports(row.title)
                    && action.equals(row.action)) {
                if (match != -1) return -1;
                match = i;
            }
        }
        return match;
    }
}
