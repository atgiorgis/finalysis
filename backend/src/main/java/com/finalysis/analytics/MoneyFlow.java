package com.finalysis.analytics;

import com.finalysis.api.ApiMoney;
import java.math.BigDecimal;

/**
 * Money moving in one bucket, signed as stored (GR-03): {@code moneyOut} is the sum of the negative amounts (≤ 0),
 * {@code moneyIn} the sum of the others (≥ 0, zero amounts included), {@code net} their sum, and {@code count} the
 * number of txns. All amounts have two decimals.
 */
public record MoneyFlow(@ApiMoney BigDecimal moneyOut, @ApiMoney BigDecimal moneyIn, @ApiMoney BigDecimal net,
                        int count) {

    public static final MoneyFlow ZERO = new MoneyFlow(Arithmetic.ZERO, Arithmetic.ZERO, Arithmetic.ZERO, 0);

    static MoneyFlow of(BigDecimal moneyOut, BigDecimal moneyIn, int count) {
        return new MoneyFlow(moneyOut, moneyIn, moneyOut.add(moneyIn), count);
    }

    MoneyFlow plus(MoneyFlow other) {
        return of(moneyOut.add(other.moneyOut), moneyIn.add(other.moneyIn), count + other.count);
    }
}
