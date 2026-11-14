package sn.recozai.platform;

import jakarta.persistence.*;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class Database {
    @PersistenceContext private EntityManager entityManager;

    public List<Tuple> rows(String sql, Object... parameters) {
        NativeQuery<Tuple> query = entityManager.unwrap(Session.class).createNativeQuery(sql, Tuple.class);
        parameters(query, parameters);
        return query.getResultList();
    }

    public int execute(String sql, Object... parameters) {
        Query query = entityManager.createNativeQuery(sql);
        parameters(query, parameters);
        return query.executeUpdate();
    }

    private void parameters(Query query, Object[] parameters) {
        for (int i = 0; i < parameters.length; i += 2)
            query.setParameter((String) parameters[i], parameters[i + 1]);
    }

    public static UUID uuid(Tuple row, String name) { return row.get(name, UUID.class); }
    public static String text(Tuple row, String name) { return row.get(name, String.class); }
    public static long number(Tuple row, String name) { return row.get(name, Number.class).longValue(); }
    public static int integer(Tuple row, String name) { return row.get(name, Number.class).intValue(); }
    public static java.math.BigDecimal decimal(Tuple row, String name) { return row.get(name, java.math.BigDecimal.class); }
    public static boolean flag(Tuple row, String name) { return row.get(name, Boolean.class); }
    public static Instant instant(Tuple row, String name) {
        Object value = row.get(name);
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime date) return date.toInstant();
        if (value instanceof java.sql.Timestamp date) return date.toInstant();
        throw new IllegalStateException("Unsupported timestamp mapping");
    }
    public static LocalDate localDate(Tuple row, String name) {
        Object value = row.get(name);
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        throw new IllegalStateException("Unsupported date mapping");
    }
}
