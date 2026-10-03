package com.hifiplayer.core.common.result

import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.error.ErrorMapper
import com.hifiplayer.core.common.error.toAppError
import com.hifiplayer.domain.model.error.AppError
import kotlinx.coroutines.CancellationException

/**
 * Explicit success/failure envelope used across repositories.
 *
 * Rationale: `try { ... } catch (e: Exception) { }` is banned by the project rules, and
 * [kotlin.Result] cannot carry our typed [AppError] with user-facing copy.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): AppError? = (this as? Failure)?.error

    fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun getOrElse(fallback: (AppError) -> @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> fallback(error)
    }
}

fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}

/**
 * Runs [block], converting any failure into a typed [Outcome.Failure].
 *
 * [CancellationException] is always rethrown so coroutine cancellation keeps working
 * (requirement 36: cancel coroutines correctly).
 */
suspend inline fun <T> outcomeOf(
    tag: String,
    mapper: ErrorMapper = ErrorMapper.Default,
    crossinline block: suspend () -> T,
): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    val error = mapper.map(throwable, tag)
    AppLogger.e(tag, "Fallo: ${error.code} - ${error.userMessage}", throwable)
    Outcome.Failure(error)
}

/** Non-suspend variant for cheap, synchronous blocks. */
inline fun <T> outcomeOfSync(
    tag: String,
    mapper: ErrorMapper = ErrorMapper.Default,
    block: () -> T,
): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    val error = mapper.map(throwable, tag)
    AppLogger.e(tag, "Fallo: ${error.code} - ${error.userMessage}", throwable)
    Outcome.Failure(error)
}
