package cn.qiyec.ragsight.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检查"架构门禁确实会拦人"。
 *
 * <p><b>为什么需要这个测试</b>：如果规则因为包名写错、包被改名等原因
 * 一个类都没匹配到，构建照样通过——门禁看起来在，实际什么都没检查。
 * 这种状态比没有门禁更糟，因为它会让人放松警惕。
 *
 * <p>所以这里故意写一条**一定会被违反**的规则，确认它真的会报错。
 * 这样"门禁有效"就是被验证过的事实，而不是一句声明。
 */
@DisplayName("架构门禁自检")
class ArchGateSelfCheckTest {

    private static final JavaClasses DOMAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.qiyec.ragsight.domain");

    @Test
    @DisplayName("故意写一条必被违反的规则，应当报错——证明门禁是活的")
    void a_violated_rule_should_actually_fail() {
        // 这条规则要求 domain 里的类只能依赖 java.io 包，
        // 而它们实际用的是 java.util、java.lang 等，必然违反。
        ArchRule deliberatelyBroken = classes()
                .that().resideInAPackage("cn.qiyec.ragsight.domain..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java.io..");

        assertThatThrownBy(() -> deliberatelyBroken.check(DOMAIN_CLASSES))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("故意写一条匹配不到任何类的规则，也应当报错——防止规则静默失效")
    void a_rule_matching_nothing_should_also_fail() {
        // 注意包名必须符合 Java 标识符规范，不能用连字符。
        // 这里用一个合法但不存在的包名。
        ArchRule matchesNothing = classes()
                .that().resideInAPackage("cn.qiyec.ragsight.nosuchpackage..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..");

        assertThatThrownBy(() -> matchesNothing.check(DOMAIN_CLASSES))
                .isInstanceOf(AssertionError.class);
    }
}
