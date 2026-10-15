package com.example.navigation_menu.service.chain;

import com.example.navigation_menu.model.vo.MenuTreeVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MenuHandlerChainTest {

    @Test
    @DisplayName("handler 按 @Order 升序执行，与构造入参顺序无关")
    void sortsHandlersByOrderAnnotation() {
        List<String> executed = new ArrayList<>();
        MenuHandlerChain chain = new MenuHandlerChain(List.of(
                new ThirdHandler(executed),
                new FirstHandler(executed),
                new SecondHandler(executed)
        ));

        chain.proceed(new MenuContext());

        assertThat(executed).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("没有 @Order 的 handler 排在最后")
    void putsHandlersWithoutOrderAtTheEnd() {
        List<String> executed = new ArrayList<>();
        MenuHandlerChain chain = new MenuHandlerChain(List.of(
                new NoOrderHandler(executed),
                new FirstHandler(executed)
        ));

        chain.proceed(new MenuContext());

        assertThat(executed).containsExactly("first", "no-order");
    }

    @Test
    @DisplayName("某个 handler 产出 result 后链立即短路，后续 handler 不再执行")
    void stopsWhenHandlerProducesResult() {
        List<String> executed = new ArrayList<>();
        MenuHandlerChain chain = new MenuHandlerChain(List.of(
                new FirstHandler(executed),
                new TerminatingHandler(executed),
                new ThirdHandler(executed)
        ));
        MenuContext context = new MenuContext();

        chain.proceed(context);

        assertThat(executed).containsExactly("first", "terminating");
        assertThat(context.getResult()).isNotNull();
    }

    @Test
    @DisplayName("没有 handler 产出 result 时，context 的 result 保持为 null")
    void leavesResultNullWhenNoHandlerProducesOne() {
        MenuContext context = new MenuContext();
        MenuHandlerChain chain = new MenuHandlerChain(List.of(new FirstHandler(new ArrayList<>())));

        chain.proceed(context);

        assertThat(context.getResult()).isNull();
    }

    @Test
    @DisplayName("空 handler 列表时 proceed 不做任何事，也不抛异常")
    void handlesEmptyHandlerList() {
        MenuContext context = new MenuContext();

        new MenuHandlerChain(List.of()).proceed(context);

        assertThat(context.getResult()).isNull();
    }

    @Order(1)
    static class FirstHandler implements MenuHandler {
        private final List<String> executed;

        FirstHandler(List<String> executed) {
            this.executed = executed;
        }

        @Override
        public void handle(MenuContext context) {
            executed.add("first");
        }
    }

    @Order(2)
    static class SecondHandler implements MenuHandler {
        private final List<String> executed;

        SecondHandler(List<String> executed) {
            this.executed = executed;
        }

        @Override
        public void handle(MenuContext context) {
            executed.add("second");
        }
    }

    @Order(3)
    static class ThirdHandler implements MenuHandler {
        private final List<String> executed;

        ThirdHandler(List<String> executed) {
            this.executed = executed;
        }

        @Override
        public void handle(MenuContext context) {
            executed.add("third");
        }
    }

    @Order(2)
    static class TerminatingHandler implements MenuHandler {
        private final List<String> executed;

        TerminatingHandler(List<String> executed) {
            this.executed = executed;
        }

        @Override
        public void handle(MenuContext context) {
            executed.add("terminating");
            context.setResult(new MenuTreeVO(List.of(), null));
        }
    }

    /** 故意不加 @Order，用于验证排在最后 */
    static class NoOrderHandler implements MenuHandler {
        private final List<String> executed;

        NoOrderHandler(List<String> executed) {
            this.executed = executed;
        }

        @Override
        public void handle(MenuContext context) {
            executed.add("no-order");
        }
    }
}
