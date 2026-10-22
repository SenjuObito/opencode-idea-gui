import type { HostEventEnvelope, HostEventPayload } from './hostEventTypes';

/**
 * Helpers that build named events for the tests.
 *
 * <p>The tests drive the handlers directly; the handlers take a named payload
 * and an envelope. These helpers keep the call sites readable: they take the
 * same values the old positional calls took and return the pair the handler
 * expects.</p>
 */
export type EventPair = [HostEventPayload, HostEventEnvelope];

function envelope(sessionId?: string | null, tabId?: string | null): HostEventEnvelope {
  const env: HostEventEnvelope = { v: 2 };
  if (sessionId) env.sessionId = sessionId;
  if (tabId) env.tabId = tabId;
  return env;
}

/** A message snapshot: {@code (sessionId, json, sequence?, baseIndex?)}. */
export function snapshotEvent(
  sessionId: string | null | undefined,
  json: string,
  sequence?: string | number,
  baseIndex?: string | number,
  tabId?: string | null,
): EventPair {
  const payload: HostEventPayload = { messages: json };
  if (sequence !== undefined) payload.sequence = sequence;
  if (baseIndex !== undefined) payload.baseIndex = baseIndex;
  return [payload, envelope(sessionId, tabId)];
}

/** A message tail: {@code (sessionId, json, baseIndex, sequence)}. */
export function tailEvent(
  sessionId: string | null | undefined,
  json: string,
  baseIndex: string | number,
  sequence?: string | number,
  tabId?: string | null,
): EventPair {
  const payload: HostEventPayload = { messages: json, baseIndex };
  if (sequence !== undefined) payload.sequence = sequence;
  return [payload, envelope(sessionId, tabId)];
}

/** A session-scoped event with named fields. */
export function sessionEvent(
  sessionId: string | null | undefined,
  fields: HostEventPayload = {},
  tabId?: string | null,
): EventPair {
  return [fields, envelope(sessionId, tabId)];
}

/** An event without a session (window-level). */
export function plainEvent(fields: HostEventPayload = {}): EventPair {
  return [fields, envelope()];
}

/** A delta: {@code (sessionId, delta)} — the session id is optional. */
export function deltaEvent(sessionId: string | null | undefined, delta: string): EventPair {
  return [{ delta }, envelope(sessionId)];
}

/** The binding event: {@code (sessionId, previousSessionId?, tabId?)}. */
export function boundEvent(
  sessionId: string,
  previousSessionId?: string,
  tabId?: string | null,
): EventPair {
  return [
    { sessionId, previousSessionId: previousSessionId ?? '' },
    envelope(sessionId, tabId),
  ];
}

/** A prompt request: the request's own fields are the payload. */
export function promptEvent(request: HostEventPayload, sessionId?: string | null): EventPair {
  return [request, envelope(sessionId, (request.tabId as string) ?? null)];
}
