package jack.digityhuman.speechtraining.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForbiddenWordCheckerTest {

    private final ForbiddenWordChecker checker = new ForbiddenWordChecker();

    @Test
    void 默认扣分_每次命中扣20() {
        ForbiddenWordChecker.ForbiddenResult result = checker.check(
                List.of("抱歉，我不清楚，再说吧"), "不清楚,再说");

        assertEquals(2, result.totalHits());
        assertEquals(60, result.score(0));
    }

    @Test
    void 自定义扣分_按每次命中扣35() {
        ForbiddenWordChecker.ForbiddenResult result = checker.check(
                List.of("不清楚，再说吧"), "不清楚,再说");

        assertEquals(30, result.score(35));
    }

    @Test
    void 交给Dify的事实_不携带扣分参数与最终得分() {
        ForbiddenWordChecker.ForbiddenResult hit = checker.check(
                List.of("不清楚，再说吧"), "不清楚,再说");
        ForbiddenWordChecker.ForbiddenResult clean = checker.check(List.of("您好"), "不清楚");

        assertTrue(hit.factText().contains("共 2 次"));
        assertFalse(hit.factText().contains("每命中一次"));
        assertFalse(hit.factText().contains("最终得分"));
        assertEquals("学员在演练中没有踩中任何违禁词。", clean.factText());
    }
}
