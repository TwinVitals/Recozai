package sn.recozai.shared;

import java.util.function.Supplier;

public interface Transactions {
    <T> T execute(Supplier<T> operation);
}
