package aimscope.boot;

import com.embabel.agent.core.AgentPlatform;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Casca imperativa mínima (espelho do App.java do beautiful-linkedin), em modo
 * BATCH: sobe o contexto Spring (web-application-type=none; o
 * embabel-agent-starter cria o AgentPlatform por autoconfigure), faz require
 * EAGER do agente na thread main (TCCL = LaunchedClassLoader do fat-jar),
 * roda a narrativa UMA vez e encerra a JVM com o exit code apropriado.
 */
@SpringBootApplication
public class App {

  public static void main(String[] args) {
    SpringApplication.run(App.class, args);
  }

  @Bean
  CommandLineRunner boot(AgentPlatform platform, ConfigurableApplicationContext ctx) {
    return args -> {
      int code = 0;
      // dispatch por argumento: "objective" interpreta o objetivo em linguagem
      // natural; default roda a narrativa. Mesmo processo batch, dois agentes.
      boolean objective = args.length > 0 && "objective".equals(args[0]);
      String ns = objective ? "aimscope.coach.objective-agent" : "aimscope.coach.insight-agent";
      String fn = objective ? "interpret!" : "narrate!";
      try {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read(ns));
        Clojure.var(ns, fn).invoke(platform);
      } catch (Throwable t) {
        System.err.println("[boot] agente " + ns + " falhou: " + t);
        t.printStackTrace();
        code = 1;
      }
      final int exitCode = code;
      System.exit(SpringApplication.exit(ctx, () -> exitCode));
    };
  }
}
