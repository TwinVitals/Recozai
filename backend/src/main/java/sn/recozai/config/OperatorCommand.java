package sn.recozai.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty("operator.grant-file")
public class OperatorCommand implements CommandLineRunner {
    private final IdentityOperator operator;
    private final ConfigurableApplicationContext context;
    private final Path grantFile;
    private final Path output;
    public OperatorCommand(IdentityOperator operator, ConfigurableApplicationContext context,
        @Value("${operator.grant-file}") String grantFile, @Value("${operator.output-file}") String output) {
        this.operator=operator; this.context=context; this.grantFile=Path.of(grantFile); this.output=Path.of(output);
    }
    @Override public void run(String... args) throws Exception {
        if (!"none".equals(context.getEnvironment().getProperty("spring.main.web-application-type")))
            throw new IllegalStateException("Operator command requires non-web mode");
        if (Files.size(grantFile)>16000) throw new IllegalArgumentException("Grant file too large");
        try (var channel=Files.newByteChannel(output,java.util.Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            String token=operator.issue(Files.readString(grantFile));
            java.nio.ByteBuffer bytes=StandardCharsets.UTF_8.encode(token);
            while (bytes.hasRemaining()) channel.write(bytes);
        }
        context.close();
    }
}
