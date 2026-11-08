"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert } from "@/components/Alert";
import { Avatar } from "@/components/Avatar";
import { Icon } from "@/components/Icon";
import { BackLink } from "@/components/PageHeader";
import { ChatSkeleton } from "@/components/Skeletons";
import { StatusPill } from "@/components/StatusPill";
import { useAuth } from "@/lib/auth-context";
import { API_BASE, apiFetch, ApiError } from "@/lib/api";
import { formatDate, formatTime } from "@/lib/format";
import { useOnlineStatus } from "@/lib/use-online";
import type { ChatMessage, GroupDetail, MessagePage, RealtimeTicket, SendResponse } from "@/lib/types";

type ChatItem = Omit<ChatMessage, "message_id" | "sequence_id"> & {
  message_id?: string;
  sequence_id?: number;
  client_msg_id?: string;
  pending?: boolean;
  failed?: boolean;
};

function mergeList(items: ChatItem[]): ChatItem[] {
  const byKey = new Map<string, ChatItem>();
  for (const item of items) {
    const key = item.message_id || "client:" + item.client_msg_id;
    const existing = byKey.get(key);
    byKey.set(key, existing ? { ...existing, ...item, pending: false } : item);
  }
  return [...byKey.values()].sort(
    (left, right) => (left.sequence_id ?? Number.MAX_SAFE_INTEGER) - (right.sequence_id ?? Number.MAX_SAFE_INTEGER),
  );
}

function websocketUrl(ticket: string): string {
  return API_BASE.replace(/^http/, "ws") + "/v1/ws?ticket=" + encodeURIComponent(ticket);
}

export default function GroupChatPage() {
  const params = useParams<{ id: string }>();
  const groupId = params.id;
  const { token, user } = useAuth();
  const online = useOnlineStatus();

  const [messages, setMessages] = useState<ChatItem[]>([]);
  const [body, setBody] = useState("");
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [olderCursor, setOlderCursor] = useState<number | null | undefined>(undefined);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const socketRef = useRef<WebSocket | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  const merge = useCallback((incoming: ChatItem[]) => {
    setMessages((previous) => mergeList([...previous, ...incoming]));
  }, []);

  const history = useQuery({
    queryKey: ["messages", groupId, token],
    queryFn: () => apiFetch<MessagePage>("/v1/groups/" + groupId + "/messages?limit=50", {}, token),
    enabled: Boolean(token),
  });

  // Sender names come from the group detail payload the rest of the app already
  // uses; the message frames only carry sender_id.
  const group = useQuery({
    queryKey: ["group", groupId, token],
    queryFn: () => apiFetch<GroupDetail>("/v1/groups/" + groupId, {}, token),
    enabled: Boolean(token),
    staleTime: 60_000,
  });

  const senderNames = useMemo(() => {
    const map = new Map<string, string>();
    for (const member of group.data?.members ?? []) {
      map.set(member.user_id, member.display_name);
    }
    return map;
  }, [group.data]);

  useEffect(() => {
    if (history.data) {
      merge(history.data.items);
      setOlderCursor((current) => (current === undefined ? history.data.next_before_seq ?? null : current));
    }
  }, [history.data, merge]);

  const refreshLatest = useCallback(async () => {
    try {
      const page = await apiFetch<MessagePage>("/v1/groups/" + groupId + "/messages?limit=50", {}, token);
      merge(page.items);
    } catch {
      // Reconnect will retry; history remains visible.
    }
  }, [groupId, merge, token]);

  useEffect(() => {
    if (!token || !user) {
      return;
    }
    let closed = false;
    let socket: WebSocket | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let attempt = 0;

    const connect = async () => {
      if (closed) {
        return;
      }
      try {
        const ticket = await apiFetch<RealtimeTicket>("/v1/realtime/tickets", {
          method: "POST",
          body: JSON.stringify({ group_id: groupId }),
        }, token);
        if (closed) {
          return;
        }
        socket = new WebSocket(websocketUrl(ticket.ticket));
        socketRef.current = socket;
        socket.onopen = () => {
          attempt = 0;
        };
        socket.onmessage = (event) => {
          try {
            const data = JSON.parse(event.data) as Record<string, unknown>;
            if (data.type === "ready") {
              setConnected(true);
              void refreshLatest();
            } else if (data.type === "msg") {
              merge([{
                message_id: String(data.message_id),
                sequence_id: Number(data.sequence_id),
                sender_id: String(data.sender_id),
                body: String(data.body),
                created_at: String(data.created_at),
              }]);
            } else if (data.type === "error") {
              setError("Message could not be sent: " + String(data.code ?? "error"));
            }
          } catch {
            // Ignore malformed frames.
          }
        };
        socket.onclose = () => {
          setConnected(false);
          if (!closed) {
            const delay = Math.min(30_000, 1_000 * 2 ** attempt++);
            timer = setTimeout(() => void connect(), delay);
          }
        };
        socket.onerror = () => setConnected(false);
      } catch (caught) {
        if (caught instanceof ApiError && (caught.status === 403 || caught.status === 404)) {
          setError("You must be an active member of this group to use chat.");
          return;
        }
        if (!closed) {
          timer = setTimeout(() => void connect(), Math.min(30_000, 1_000 * 2 ** attempt++));
        }
      }
    };

    void connect();
    const online = () => {
      if (!socket || socket.readyState === WebSocket.CLOSED) {
        void connect();
      }
    };
    window.addEventListener("online", online);
    return () => {
      closed = true;
      if (timer) {
        clearTimeout(timer);
      }
      window.removeEventListener("online", online);
      socket?.close();
      socketRef.current = null;
    };
  }, [groupId, merge, refreshLatest, token, user]);

  const send = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const text = body.trim();
    if (!text || !user) {
      return;
    }
    if (!navigator.onLine) {
      setError("You are offline. Reconnect to send this message; drafts are not queued.");
      return;
    }
    const clientId = crypto.randomUUID();
    setBody("");
    setError(null);
    setMessages((previous) => mergeList([
      ...previous,
      {
        client_msg_id: clientId,
        sender_id: user.id,
        body: text,
        created_at: new Date().toISOString(),
        pending: true,
      },
    ]));
    try {
      const sent = await apiFetch<SendResponse>("/v1/groups/" + groupId + "/messages", {
        method: "POST",
        body: JSON.stringify({ body: text, client_msg_id: clientId }),
      }, token);
      setMessages((previous) => mergeList([
        ...previous.filter((message) => message.client_msg_id !== clientId),
        {
          message_id: sent.message_id,
          sequence_id: sent.sequence_id,
          sender_id: user.id,
          body: text,
          created_at: sent.created_at,
        },
      ]));
    } catch (caught) {
      setMessages((previous) => previous.map((message) =>
        message.client_msg_id === clientId ? { ...message, pending: false, failed: true } : message));
      setError(caught instanceof ApiError ? caught.message : "Message was not sent.");
    }
  };

  const loadOlder = async () => {
    if (olderCursor === null || olderCursor === undefined) {
      return;
    }
    setLoadingOlder(true);
    try {
      const page = await apiFetch<MessagePage>(
        "/v1/groups/" + groupId + "/messages?limit=50&before_seq=" + olderCursor,
        {},
        token,
      );
      merge(page.items);
      setOlderCursor(page.next_before_seq ?? null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not load older messages.");
    } finally {
      setLoadingOlder(false);
    }
  };

  const visibleMessages = useMemo(() => mergeList(messages), [messages]);

  useEffect(() => {
    const element = scrollRef.current;
    if (element) {
      element.scrollTop = element.scrollHeight;
    }
  }, [visibleMessages.length]);

  if (!token) {
    return (
      <div className="card flex flex-col items-center px-6 py-12 text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-soft text-brand">
          <Icon name="message" size={20} />
        </span>
        <h1 className="mt-4 font-display text-h2 text-ink">Sign in to open the group chat</h1>
        <p className="mt-2 max-w-sm text-body text-muted">
          Conversations are private to active members of a circle.
        </p>
        <Link href="/login" className="btn-primary mt-5">
          Sign in
        </Link>
      </div>
    );
  }

  const groupName = group.data?.group.name;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <BackLink href={"/groups/" + groupId}>{groupName ?? "Back to group"}</BackLink>
          <h1 className="mt-1 font-display text-h1 text-ink">Group conversation</h1>
          <p className="mt-1 text-small text-muted">
            {group.data ? `${group.data.group.active_member_count} members · ${group.data.group.category}` : "Private group chat"}
          </p>
        </div>
        <StatusPill label={connected ? "Live" : "Reconnecting"} tone={connected ? "positive" : "warning"} />
      </div>

      {error ? <Alert tone="danger">{error}</Alert> : null}
      {history.isError ? (
        <Alert tone="danger">
          {history.error instanceof ApiError && history.error.status === 403
            ? "You are not an active member of this group."
            : "Could not load message history."}
        </Alert>
      ) : null}

      <div className="card flex h-[calc(100dvh-19rem)] flex-col overflow-hidden sm:h-[calc(100dvh-18rem)] md:h-[calc(100dvh-11rem)] md:min-h-[26rem]">
        <div ref={scrollRef} className="flex-1 overflow-y-auto px-3 py-4 sm:px-5">
          {history.isPending ? <ChatSkeleton /> : null}
          {!history.isPending && olderCursor !== null && olderCursor !== undefined ? (
            <div className="mb-3 flex justify-center">
              <button
                type="button"
                className="btn-secondary btn-sm"
                onClick={() => void loadOlder()}
                disabled={loadingOlder}
              >
                {loadingOlder ? "Loading…" : "Load older messages"}
              </button>
            </div>
          ) : null}

          {!history.isPending && visibleMessages.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center px-4 text-center">
              <span className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-soft text-brand">
                <Icon name="message" size={20} />
              </span>
              <p className="mt-3 text-small font-medium text-ink">No messages yet. Say hello.</p>
              <p className="mt-1 max-w-xs text-small text-muted">
                This is the start of your group&rsquo;s conversation.
              </p>
            </div>
          ) : (
            visibleMessages.map((message, index) => {
              const own = message.sender_id === user?.id;
              const previous = visibleMessages[index - 1];
              const sameSender = previous !== undefined && previous.sender_id === message.sender_id;
              const withinRun = sameSender
                && new Date(message.created_at).getTime() - new Date(previous.created_at).getTime() < 5 * 60 * 1000;
              // One name per run: consecutive messages from the same sender within
              // five minutes keep the header of the run only.
              const showName = !own && !withinRun;
              const senderName = senderNames.get(message.sender_id) ?? "Member";
              const newDay = previous === undefined || formatDate(previous.created_at) !== formatDate(message.created_at);

              return (
                <div key={message.message_id || "client:" + message.client_msg_id}>
                  {newDay ? (
                    <div className="my-4 flex items-center gap-3" aria-hidden="true">
                      <span className="h-px flex-1 bg-line" />
                      <span className="text-[0.6875rem] font-semibold uppercase tracking-[0.1em] text-faint">
                        {formatDate(message.created_at)}
                      </span>
                      <span className="h-px flex-1 bg-line" />
                    </div>
                  ) : null}
                  <div className={`flex items-end gap-2 ${own ? "justify-end" : "justify-start"} ${withinRun ? "mt-1" : "mt-3"}`}>
                    {!own ? (
                      <span className={withinRun ? "w-7 shrink-0" : "shrink-0"}>
                        {!withinRun ? <Avatar name={senderName} size="xs" /> : null}
                      </span>
                    ) : null}
                    <div className={`max-w-[82%] rounded-2xl px-3.5 py-2.5 text-sm shadow-sm sm:max-w-[70%] ${
                      own
                        ? "rounded-br-sm bg-forest text-white"
                        : "rounded-bl-sm border border-line bg-canvas text-ink"
                    } ${message.pending ? "opacity-70" : ""} ${message.failed ? "border-danger/40" : ""}`}>
                      {showName ? (
                        <p className="mb-0.5 text-[0.6875rem] font-semibold text-brand">
                          {senderName}
                        </p>
                      ) : null}
                      <p className="whitespace-pre-wrap break-words leading-relaxed">{message.body}</p>
                      <p className={`mt-1 flex items-center justify-end gap-1 text-[0.625rem] ${
                        own ? "text-white/60" : "text-faint"
                      }`}>
                        {message.failed ? (
                          <>
                            <Icon name="alert-circle" size={11} /> Not sent
                          </>
                        ) : message.pending ? (
                          "Sending…"
                        ) : (
                          formatTime(message.created_at)
                        )}
                      </p>
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </div>

        <form className="flex items-end gap-2 border-t border-line bg-surface p-3 sm:p-4" onSubmit={send}>
          <label className="sr-only" htmlFor="chat-message">Message</label>
          <input
            id="chat-message"
            className="field flex-1 rounded-xl"
            placeholder={online ? "Write a message…" : "Offline — reconnect to send"}
            maxLength={2000}
            value={body}
            onChange={(event) => setBody(event.target.value)}
            autoComplete="off"
          />
          <button
            type="submit"
            className="btn-primary h-11 w-11 shrink-0 px-0 sm:w-auto sm:px-5"
            disabled={!body.trim() || !online}
            aria-label="Send"
          >
            <Icon name="send" size={17} />
            <span className="hidden sm:inline">Send</span>
          </button>
        </form>
      </div>
    </div>
  );
}
