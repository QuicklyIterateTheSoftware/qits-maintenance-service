package eu.wohlben.qits.maintenance.peer;

/**
 * What a peer said as raw bytes, or why it said nothing — {@link PeerAnswer} for a body that is not
 * text (qits-2702: a consumer pact jar).
 *
 * <p>{@link PeerAnswer} decodes every body as UTF-8, which corrupts a zip; this carries the bytes
 * untouched. The same rule holds: a transport failure is an answer, never an exception.
 *
 * @param url what was read, so a surprising answer can be reproduced by hand
 * @param httpStatus the peer's status code, or null when the call never got one
 * @param body the response body, bounded by {@link PeerClient#RESPONSE_LIMIT_BYTES}; null on a
 *     failure
 * @param error the transport failure, or null when the call completed
 */
public record PeerBytes(String url, Integer httpStatus, byte[] body, String error) {

  /** Whether the peer answered 2xx with a body. */
  public boolean ok() {
    return error == null && httpStatus != null && httpStatus >= 200 && httpStatus < 300;
  }

  /** Whether the peer answered 404. */
  public boolean notFound() {
    return httpStatus != null && httpStatus == 404;
  }

  /** The call could not be made at all, or was answered with something other than 2xx. */
  public String failure() {
    if (error != null) {
      return error;
    }
    return "HTTP " + httpStatus;
  }
}
