package com.testplatform.ai;

import com.testplatform.db.AiCase;
import com.testplatform.db.AiCaseStore;

import java.util.List;
import java.util.Scanner;

/**
 * AI 用例复核控制台（人工把关环节）
 *
 * 运行：mvn compile exec:java -Dexec.mainClass=com.testplatform.ai.AiCaseReviewConsole
 *
 * 设计原则：
 *   - pending 用例必须人显式 approve/reject，没有“自动通过”路径
 *   - approve 才进回归集；rejected 永不进——AI 胡编的用例永远执行不到
 *   - 复核动作可重复提交：同一 id 的 approve 后再 reject 会因 SQL 守门（status='pending'）写不进
 */
public class AiCaseReviewConsole {

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in, "utf-8");
        while (true) {
            List<AiCase> pending = AiCaseStore.listByStatus(AiCase.PENDING);
            if (pending.isEmpty()) {
                System.out.println("没有待复核用例");
                return;
            }
            System.out.println("\n===== 待复核用例（共 " + pending.size() + " 条）=====");
            for (int i = 0; i < pending.size(); i++) {
                AiCase c = pending.get(i);
                System.out.printf("%n[%d] id=%d | %s%n  契约: %s%n  请求: %s%n  期望: %s%n",
                        i + 1, c.id(), c.caseName(), c.contractPath(),
                        truncate(c.requestJson(), 120), truncate(c.expectJson(), 80));
            }
            System.out.print("\n输入序号审核（a=approve r=reject，如 2a 表示第 2 条通过；q=退出）：");
            String line = sc.nextLine().trim();
            if ("q".equalsIgnoreCase(line)) {
                return;
            }
            if (line.length() < 2) {
                System.out.println("格式：序号+a/r，如 2a");
                continue;
            }
            int idx;
            try {
                idx = Integer.parseInt(line.substring(0, line.length() - 1)) - 1;
            } catch (NumberFormatException e) {
                System.out.println("无效序号");
                continue;
            }
            if (idx < 0 || idx >= pending.size()) {
                System.out.println("序号越界");
                continue;
            }
            String action = line.substring(line.length() - 1).toLowerCase();
            String target;
            if ("a".equals(action)) {
                target = AiCase.APPROVED;
            } else if ("r".equals(action)) {
                target = AiCase.REJECTED;
                System.out.print("驳回原因：");
                String note = sc.nextLine().trim();
                review(pending.get(idx).id(), target, note);
                continue;
            } else {
                System.out.println("未知动作：" + action);
                continue;
            }
            review(pending.get(idx).id(), target, null);
        }
    }

    private static void review(long id, String target, String note) throws Exception {
        int n = AiCaseStore.review(id, target, note);
        if (n == 0) {
            System.out.println("用例不存在或已被复核过（幂等保护）");
        } else {
            System.out.println("已" + (AiCase.APPROVED.equals(target) ? "通过" : "驳回"));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
