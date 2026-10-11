package com.codebaseai.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.codebaseai.backend.config.AppProperties;

/**
 * Guards the Spring wiring of {@link AiServiceClient}.
 *
 * <p>This class once carried BOTH {@code @RequiredArgsConstructor} and an
 * explicit constructor, so Lombok generated a second (AppProperties, WebClient)
 * constructor alongside the explicit (AppProperties) one. Spring saw two
 * constructors, none {@code @Autowired}, and no default - so it failed to
 * instantiate the bean at startup with "No default constructor found". The
 * trivial {@code BackendApplicationTests} does not load the context, so that
 * class of bug is invisible there; these assertions catch it in unit form.
 */
class AiServiceClientTest {

    @Test
    void hasExactlyOneConstructorSoSpringCanAutowireIt() {
        assertEquals(1, AiServiceClient.class.getDeclaredConstructors().length,
                "AiServiceClient must expose exactly one constructor (Lombok must not "
                        + "generate a second one alongside the explicit constructor)");
    }

    @Test
    void springCanInstantiateTheBean() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(AppProperties.class, AiServiceClient.class);
            context.refresh();
            assertNotNull(context.getBean(AiServiceClient.class),
                    "Spring must be able to construct AiServiceClient (it once failed "
                            + "with 'No default constructor found')");
        }
    }
}