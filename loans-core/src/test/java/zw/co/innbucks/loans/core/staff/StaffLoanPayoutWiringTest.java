package zw.co.innbucks.loans.core.staff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import zw.co.innbucks.loans.core.DisbursementService;
import zw.co.innbucks.loans.core.disbursements.InnbucksDisbursementService;
import zw.co.innbucks.loans.core.disbursements.InnbucksLoanInquiryClient;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanJourneyService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutDispatcher;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutRequests;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatusJob;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatusService;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Staff Grocery Loan's cash payout is sent from one place: {@link StaffLoanPayoutService}, on
 * {@link StaffLoanPayoutDispatcher}'s one thread. The bank books AND pays on that call, so:
 * <ul>
 *   <li>neither is a scheduled job, nor runs on {@code @Async}, nor is what the portal asks through
 *       ({@link StaffLoanPayoutRequests}): nothing sends a payout on a timer, and every send is one a borrower's
 *       acceptance or a Finance user asked for;</li>
 *   <li>the status job and the status service can reach nothing that books: every bean they depend on, and every
 *       bean those depend on, is read here from their constructors, so a dependency added three classes away
 *       still fails this test;</li>
 *   <li>the acceptance takes neither the bank's client nor the payout service: it only asks the dispatcher to pay
 *       after it commits.</li>
 * </ul>
 */
class StaffLoanPayoutWiringTest {

    /** What books with the bank, or sends a payout. */
    private static final List<Class<?>> PAYING = List.of(DisbursementService.class, InnbucksDisbursementService.class,
            StaffLoanPayoutService.class, StaffLoanPayoutDispatcher.class);

    private static final Set<Class<?>> COMPONENTS = components();

    @Test
    @DisplayName("the payout sender, its dispatcher and the portal's requests are not scheduled, and not @Async")
    void theSenderIsNeverScheduled() {
        for (Class<?> sender : List.of(StaffLoanPayoutService.class, StaffLoanPayoutDispatcher.class,
                StaffLoanPayoutRequests.class)) {
            assertThat(SchedulingConfigurer.class.isAssignableFrom(sender)).as(sender.getSimpleName()).isFalse();
            assertThat(Arrays.stream(sender.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Scheduled.class)
                            || method.isAnnotationPresent(Async.class))
                    .toList()).as(sender.getSimpleName()).isEmpty();
            assertThat(sender.isAnnotationPresent(Async.class)).as(sender.getSimpleName()).isFalse();
        }
    }

    @Test
    @DisplayName("the status job and the status service reach nothing that books, however far down")
    void theStatusCheckReachesNothingThatPays() {
        for (Class<?> root : List.of(StaffLoanPayoutStatusJob.class, StaffLoanPayoutStatusService.class)) {
            Set<Class<?>> reached = reachable(root);
            assertThat(reached).as("the walk follows the inquiry to its client").contains(InnbucksLoanInquiryClient.class);
            for (Class<?> paying : PAYING) {
                assertThat(reached.stream().filter(paying::isAssignableFrom).toList())
                        .as(root.getSimpleName() + " must not reach " + paying.getSimpleName()).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("the walk would find a paying class: the dispatcher reaches the bank's client")
    void theWalkFindsWhatItLooksFor() {
        assertThat(reachable(StaffLoanPayoutDispatcher.class)).contains(InnbucksDisbursementService.class,
                StaffLoanPayoutService.class);
    }

    @Test
    @DisplayName("the acceptance takes neither the bank's client nor the payout service")
    void theAcceptanceNeverPays() {
        Set<Class<?>> direct = Arrays.stream(StaffLoanJourneyService.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .collect(Collectors.toSet());
        assertThat(direct).doesNotContain(InnbucksDisbursementService.class, DisbursementService.class,
                StaffLoanPayoutService.class);
    }

    /**
     * Every class of loans {@code root} depends on, transitively: its constructors' parameters and its
     * {@code @Autowired} fields, generic arguments ({@code ObjectProvider<X>}) included. An interface or abstract
     * class stands for every component that implements it.
     */
    private static Set<Class<?>> reachable(Class<?> root) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            Class<?> type = todo.pop();
            if (!seen.add(type)) {
                continue;
            }
            Set<Type> needs = new HashSet<>();
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                needs.addAll(Arrays.asList(constructor.getGenericParameterTypes()));
            }
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    needs.add(field.getGenericType());
                }
            }
            for (Type need : needs) {
                for (Class<?> dependency : ours(need)) {
                    todo.addAll(implementations(dependency));
                }
            }
        }
        return seen;
    }

    /** The classes of loans a type names, its generic arguments included. */
    private static Set<Class<?>> ours(Type type) {
        Set<Class<?>> classes = new HashSet<>();
        if (type instanceof Class<?> raw) {
            if (raw.getName().startsWith("zw.co.innbucks.loans.")) {
                classes.add(raw);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            classes.addAll(ours(parameterized.getRawType()));
            for (Type argument : parameterized.getActualTypeArguments()) {
                classes.addAll(ours(argument));
            }
        }
        return classes;
    }

    private static Set<Class<?>> implementations(Class<?> type) {
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            return Set.of(type);
        }
        Set<Class<?>> found = COMPONENTS.stream().filter(type::isAssignableFrom).collect(Collectors.toSet());
        found.add(type);
        return found;
    }

    private static Set<Class<?>> components() {
        AnnotationTypeFilter components = new AnnotationTypeFilter(Component.class);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                return components.match(reader, getMetadataReaderFactory());
            }
        };
        return scanner.findCandidateComponents("zw.co.innbucks.loans").stream()
                .map(definition -> ClassUtils.resolveClassName(definition.getBeanClassName(), null))
                .collect(Collectors.toSet());
    }
}
