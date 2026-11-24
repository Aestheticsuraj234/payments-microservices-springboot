package dev.payments.wallet.money;

/**
 * An amount of money: a whole number of minor units of a currency, like cents
 * for USD or yen for JPY.
 *
 * <p>It is a {@code long}, never a {@code double}: {@code 0.1 + 0.2} is not
 * {@code 0.3} in floating point, and nobody wants to lose a cent to rounding.
 * And an amount never travels without its currency: 500 alone could be five
 * dollars or five hundred yen.
 */
public record Money(long amount, String currency) {
}
