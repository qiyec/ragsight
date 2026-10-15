package cn.qiyec.ragsight.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web 服务的入口。
 *
 * <p>启动后会托管一个单页界面，可以在浏览器里调参数、看热力图。
 *
 * <p><b>这个项目为什么需要 Web 层</b>：诊断结果是一张 9 到 12 行的表格，
 * 而且天然适合画成热力图。命令行只能打印文本，看不出"哪个组合好、
 * 切分有多大影响"。可视化不是锦上添花——诊断工具的产出是给人看的。
 *
 * <p>完整理由和被否掉的方案见
 * {@code docs/adr/005-web-layer-and-spring-boot.md}。
 */
@SpringBootApplication
public class RagsightWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagsightWebApplication.class, args);
    }
}
