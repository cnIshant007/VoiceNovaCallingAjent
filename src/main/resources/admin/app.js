const { useEffect, useMemo, useState } = React;
const html = htm.bind(React.createElement);

const sections = [
  { id: "overview", label: "Overview", icon: "O1" },
  { id: "dialer", label: "Dialer", icon: "DL" },
  { id: "hr", label: "HR", icon: "HR" },
  { id: "contacts", label: "Contacts", icon: "CT" },
  { id: "calls", label: "Calls", icon: "CL" },
  { id: "training", label: "Training", icon: "TR" },
  { id: "system", label: "System", icon: "SY" },
];

const initialDialerForm = {
  to: "",
  language: "hi-IN",
  hr_call_type: "INTERVIEW",
  hr_notes: "",
  batch_numbers: "",
  contact_name: "",
  contact_email: "",
  contact_department: "",
  contact_designation: "",
  contact_notes: "",
  contact_tags: "",
  interview_round: "",
  interview_details: "",
  salary_credited: "yes",
  salary_amount: "",
  attendance_date: "",
  privacy_change_summary: "",
  other_faq_question_1: "",
  other_faq_answer_1: "",
  other_faq_question_2: "",
  other_faq_answer_2: "",
};

const initialContactForm = {
  phone_number: "",
  name: "",
  email: "",
  department: "",
  designation: "",
  preferred_language: "hi-IN",
  company: "",
  notes: "",
  tags: "",
};

const initialTrainingForm = {
  category: "faq",
  title: "",
  content: "",
};

const initialVoiceBackendForm = {
  voice_backend: "twilio_native",
};

const initialTtsProviderForm = {
  tts_provider: "piper",
  piper_voice: "lessac",
};

const initialLlmForm = {
  llm_profile_id: "",
  llm_provider: "local",
  llm_model: "gemma:2b",
  llm_api_url: "http://127.0.0.1:11434/api/chat",
  llm_api_format: "ollama",
  llm_api_key: "",
};

const initialLlmTestForm = {
  prompt: "Reply in one short sentence: say hello and ask how you can help.",
  max_tokens: 120,
};

const initialAssistForm = {
  expert_number: "",
  monitor_number: "",
};

function valueOrFallback(value, fallback) {
  return value == null ? fallback : value;
}

function getValue(object, path, fallback) {
  let current = object;
  for (let index = 0; index < path.length; index += 1) {
    if (current == null) {
      return fallback;
    }
    current = current[path[index]];
  }
  return current == null ? fallback : current;
}

function formatTime(value) {
  if (!value) return "Unavailable";
  return new Date(value).toLocaleString();
}

function formatDuration(seconds) {
  const safe = Math.max(0, seconds || 0);
  const minutes = Math.floor(safe / 60).toString().padStart(2, "0");
  const remainder = Math.floor(safe % 60).toString().padStart(2, "0");
  return `${minutes}:${remainder}`;
}

function titleCase(text) {
  return (text || "")
    .replace(/_/g, " ")
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

function isLongValue(value) {
  return typeof value === "string" && (value.includes("\n") || value.length > 140);
}

function llmPresetForProvider(provider) {
  const value = (provider || "").toLowerCase();
  if (value === "google") {
    return {
      llm_provider: "google",
      llm_model: "gemini-2.5-flash",
      llm_api_url: "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
      llm_api_format: "openai",
    };
  }
  if (value === "openai") {
    return {
      llm_provider: "openai",
      llm_model: "gpt-4o-mini",
      llm_api_url: "https://api.openai.com/v1/chat/completions",
      llm_api_format: "openai",
    };
  }
  if (value === "anthropic") {
    return {
      llm_provider: "anthropic",
      llm_model: "claude-sonnet-4-20250514",
      llm_api_url: "https://api.anthropic.com/v1/messages",
      llm_api_format: "openai",
    };
  }
  return {
    llm_provider: "local",
    llm_model: "gemma:2b",
    llm_api_url: "http://127.0.0.1:11434/api/chat",
    llm_api_format: "ollama",
  };
}

function localProfileById(status, profileId) {
  const profiles = getValue(status, ["available_local_profiles"], []);
  return profiles.find((profile) => profile.id === profileId) || null;
}

function statusBadgeClass(status) {
  const value = (status || "").toLowerCase();
  if (value.includes("active") || value.includes("ready") || value.includes("completed")) return "badge-green";
  if (value.includes("failed") || value.includes("error") || value.includes("busy")) return "badge-coral";
  if (value.includes("mock") || value.includes("pending")) return "badge-amber";
  return "badge-sky";
}

function isLiveCallStatus(status) {
  const value = (status || "").toLowerCase();
  return ["initiated", "mock_initiated", "queued", "ringing", "active", "in_progress"].indexOf(value) >= 0;
}

async function fetchJson(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Request failed with ${response.status}`);
  }

  return response.status === 204 ? null : response.json();
}

function App() {
  const [section, setSection] = useState("overview");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [toast, setToast] = useState(null);
  const [state, setState] = useState({
    health: null,
    status: null,
    analytics: null,
    context: null,
    history: [],
    learnedFacts: [],
    contacts: [],
    hrCandidates: [],
    hrCallHistory: [],
  });
  const [hrFilter, setHrFilter] = useState({ date_from: "", date_to: "" });
  const [loadingHrCandidates, setLoadingHrCandidates] = useState(false);
  const [hrCallFilter, setHrCallFilter] = useState({ call_type: "", date_from: "", date_to: "" });
  const [loadingHrCalls, setLoadingHrCalls] = useState(false);
  const [selectedCall, setSelectedCall] = useState(null);
  const [callDebug, setCallDebug] = useState(null);
  const [callDebugLoading, setCallDebugLoading] = useState(false);
  const [dialerForm, setDialerForm] = useState(initialDialerForm);
  const [contactForm, setContactForm] = useState(initialContactForm);
  const [savingContact, setSavingContact] = useState(false);
  const [dialingCall, setDialingCall] = useState(false);
  const [endingCallSid, setEndingCallSid] = useState("");
  const [activeDialSession, setActiveDialSession] = useState(null);
  const [trainingForm, setTrainingForm] = useState(initialTrainingForm);
  const [savingTraining, setSavingTraining] = useState(false);
  const [voiceBackendForm, setVoiceBackendForm] = useState(initialVoiceBackendForm);
  const [savingVoiceBackend, setSavingVoiceBackend] = useState(false);
  const [ttsProviderForm, setTtsProviderForm] = useState(initialTtsProviderForm);
  const [savingTtsProvider, setSavingTtsProvider] = useState(false);
  const [llmForm, setLlmForm] = useState(initialLlmForm);
  const [savingLlmSettings, setSavingLlmSettings] = useState(false);
  const [llmTestForm, setLlmTestForm] = useState(initialLlmTestForm);
  const [testingLlm, setTestingLlm] = useState(false);
  const [llmTestResult, setLlmTestResult] = useState(null);
  const [assistForm, setAssistForm] = useState(initialAssistForm);
  const [connectingExpert, setConnectingExpert] = useState(false);
  const [connectingMonitor, setConnectingMonitor] = useState(false);

  const activeCalls = useMemo(
    () => (state.history || []).filter((item) => item.status === "active"),
    [state.history]
  );

  const metrics = useMemo(() => {
    const analytics = state.analytics || {};
    const context = state.context || {};
    return [
      {
        label: "Active Calls",
        value: valueOrFallback(analytics.active_calls, 0),
        meta: "Current phone sessions being tracked by the backend.",
        progress: Math.min(100, (analytics.active_calls || 0) * 10),
      },
      {
        label: "Completed Calls",
        value: valueOrFallback(analytics.completed_calls, 0),
        meta: "Calls that reached a final completion status.",
        progress: Math.min(100, analytics.success_rate || 0),
      },
      {
        label: "Knowledge Chunks",
        value: valueOrFallback(context.totalChunks, 0),
        meta: `${valueOrFallback(context.documentsLoaded, 0)} source documents loaded for the agent.`,
        progress: Math.min(100, ((context.totalChunks || 0) / 20) * 100),
      },
      {
        label: "Credits Remaining",
        value: valueOrFallback(analytics.credits_remaining, 0),
        meta: `Limit ${valueOrFallback(analytics.credits_limit, 0)} with ${valueOrFallback(analytics.messages_processed, 0)} processed turns.`,
        progress: analytics.credits_limit
          ? Math.min(100, ((analytics.credits_remaining || 0) / analytics.credits_limit) * 100)
          : 0,
      },
    ];
  }, [state.analytics, state.context]);

  async function loadEverything(showSpinner = false) {
    try {
      if (showSpinner) {
        setRefreshing(true);
      } else {
        setLoading(true);
      }

      const [health, status, llmStatus, analytics, context, history, learnedFacts, contacts, hrCandidates, hrCallHistory] = await Promise.all([
        fetchJson("/api/v1/health"),
        fetchJson("/api/v1/status"),
        fetchJson("/api/v1/system/llm"),
        fetchJson("/api/v1/analytics"),
        fetchJson("/api/v1/test/context-summary"),
        fetchJson("/api/v1/calls/history?limit=30"),
        fetchJson("/api/v1/training/learned-facts?limit=30"),
        fetchJson("/api/v1/hr/contacts?limit=200"),
        fetchJson("/api/v1/hr/candidates?limit=200"),
        fetchJson("/api/v1/hr/calls/history?limit=200"),
      ]);

      const mergedStatus = {
        ...(status || {}),
        ...(llmStatus || {}),
      };

      setState({ health, status: mergedStatus, analytics, context, history, learnedFacts, contacts, hrCandidates, hrCallHistory });
      setVoiceBackendForm({
        voice_backend: getValue(mergedStatus, ["selected_voice_backend"], "twilio_native"),
      });
      setTtsProviderForm({
        tts_provider: getValue(mergedStatus, ["selected_tts_provider"], "piper"),
        piper_voice: getValue(mergedStatus, ["selected_piper_voice"], "amy"),
      });
      setLlmForm({
        llm_profile_id: getValue(mergedStatus, ["selected_local_profile_id"], ""),
        llm_provider: getValue(mergedStatus, ["llm_provider"], "local"),
        llm_model: getValue(mergedStatus, ["llm_model"], "gemma:2b"),
        llm_api_url: getValue(
          mergedStatus,
          ["llm_api_url"],
          "http://127.0.0.1:11434/api/chat"
        ),
        llm_api_format: getValue(mergedStatus, ["llm_api_format"], "ollama"),
        llm_api_key: "",
      });
    } catch (error) {
      showToast(error.message || "Could not load admin data.");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  async function loadHrCandidatesWithFilter() {
    try {
      setLoadingHrCandidates(true);
      const params = new URLSearchParams();
      if ((hrFilter.date_from || "").trim()) params.set("date_from", hrFilter.date_from.trim());
      if ((hrFilter.date_to || "").trim()) params.set("date_to", hrFilter.date_to.trim());
      params.set("limit", "500");
      const rows = await fetchJson(`/api/v1/hr/candidates?${params.toString()}`);
      setState((prev) => ({ ...prev, hrCandidates: rows || [] }));
    } catch (error) {
      showToast(error.message || "Could not load HR candidates.");
    } finally {
      setLoadingHrCandidates(false);
    }
  }

  async function loadHrCallsWithFilter() {
    try {
      setLoadingHrCalls(true);
      const params = new URLSearchParams();
      if ((hrCallFilter.call_type || "").trim()) params.set("call_type", hrCallFilter.call_type.trim());
      if ((hrCallFilter.date_from || "").trim()) params.set("date_from", hrCallFilter.date_from.trim());
      if ((hrCallFilter.date_to || "").trim()) params.set("date_to", hrCallFilter.date_to.trim());
      params.set("limit", "500");
      const rows = await fetchJson(`/api/v1/hr/calls/history?${params.toString()}`);
      setState((prev) => ({ ...prev, hrCallHistory: rows || [] }));
    } catch (error) {
      showToast(error.message || "Could not load HR call history.");
    } finally {
      setLoadingHrCalls(false);
    }
  }

  function buildScenarioPayload() {
    const faqs = [
      { question: dialerForm.other_faq_question_1, answer: dialerForm.other_faq_answer_1 },
      { question: dialerForm.other_faq_question_2, answer: dialerForm.other_faq_answer_2 },
    ]
      .map((item) => ({ question: (item.question || "").trim(), answer: (item.answer || "").trim() }))
      .filter((item) => item.question && item.answer);

    return {
      call_type: dialerForm.hr_call_type || "INTERVIEW",
      interview_round: dialerForm.interview_round.trim() || null,
      interview_details: dialerForm.interview_details.trim() || null,
      salary_credited:
        dialerForm.hr_call_type === "SALARY" ? dialerForm.salary_credited === "yes" : null,
      salary_amount: dialerForm.salary_amount.trim() || null,
      attendance_date: dialerForm.attendance_date.trim() || null,
      privacy_change_summary: dialerForm.privacy_change_summary.trim() || null,
      custom_faqs: faqs,
      notes: dialerForm.hr_notes.trim() || null,
    };
  }

  async function saveContact(event) {
    event.preventDefault();
    if (!contactForm.phone_number.trim()) {
      showToast("Enter a contact phone number first.");
      return;
    }
    try {
      setSavingContact(true);
      await fetchJson("/api/v1/hr/contacts", {
        method: "POST",
        body: JSON.stringify({
          phone_number: contactForm.phone_number.trim(),
          name: contactForm.name.trim() || null,
          email: contactForm.email.trim() || null,
          department: contactForm.department.trim() || null,
          designation: contactForm.designation.trim() || null,
          preferred_language: contactForm.preferred_language,
          company: contactForm.company.trim() || null,
          notes: contactForm.notes.trim() || null,
          tags: (contactForm.tags || "").split(",").map((item) => item.trim()).filter(Boolean),
        }),
      });
      setContactForm(initialContactForm);
      showToast("Contact saved.");
      await loadEverything(true);
    } catch (error) {
      showToast(error.message || "Could not save contact.");
    } finally {
      setSavingContact(false);
    }
  }

  async function deleteContact(phone) {
    if (!phone || !confirm("Delete this contact?")) return;
    try {
      const res = await fetch(`/api/v1/hr/contacts/${encodeURIComponent(phone)}`, { method: "DELETE" });
      if (!res.ok) throw new Error("Failed to delete contact");
      showToast("Contact deleted.");
      await loadEverything(true);
    } catch (error) {
      showToast(error.message || "Could not delete contact.");
    }
  }

  async function deleteHrCandidate(phone) {
    if (!confirm("Are you sure you want to delete this candidate?")) return;
    try {
      const res = await fetch(`/api/v1/hr/candidates/${encodeURIComponent(phone)}`, { method: "DELETE" });
      if (!res.ok) throw new Error("Failed to delete candidate");
      showToast("Candidate deleted successfully");
      loadHrCandidatesWithFilter(); 
    } catch (err) {
      showToast(err.message || "Delete failed");
    }
  }

  async function loadCallDebug(callSid) {
    if (!callSid) return;
    try {
      setCallDebugLoading(true);
      setSelectedCall(callSid);
      const debug = await fetchJson(`/api/v1/calls/${encodeURIComponent(callSid)}/debug`);
      setCallDebug(debug);
    } catch (error) {
      setCallDebug(null);
      showToast(error.message || "Could not load call debug.");
    } finally {
      setCallDebugLoading(false);
    }
  }

  async function startOutboundCall(event, options = { hrMode: false }) {
    event.preventDefault();
    if (!dialerForm.to.trim()) {
      showToast("Enter a phone number before starting the call.");
      return;
    }

    try {
      setDialingCall(true);
      const result = await fetchJson("/api/v1/calls/outbound", {
        method: "POST",
        body: JSON.stringify({
          to: dialerForm.to.trim(),
          language: dialerForm.language,
          hr_call_type: options.hrMode ? (dialerForm.hr_call_type || "INTERVIEW") : null,
          hr_notes: options.hrMode ? (dialerForm.hr_notes || null) : null,
          contact_name: dialerForm.contact_name.trim() || null,
          contact_email: dialerForm.contact_email.trim() || null,
          contact_department: dialerForm.contact_department.trim() || null,
          contact_designation: dialerForm.contact_designation.trim() || null,
          contact_notes: dialerForm.contact_notes.trim() || null,
          contact_tags: (dialerForm.contact_tags || "").split(",").map((item) => item.trim()).filter(Boolean),
          scenario: options.hrMode ? buildScenarioPayload() : null,
        }),
      });
      setActiveDialSession(result);
      setSelectedCall(result.call_sid);
      setSection("dialer");
      showToast(`${options.hrMode ? "HR" : "Admin"} call started for ${result.to}.`);
      await loadEverything(true);
    } catch (error) {
      showToast(error.message || "Could not start outbound call.");
    } finally {
      setDialingCall(false);
    }
  }

  async function startHrBatchCalls(event) {
    event.preventDefault();
    const numbers = (dialerForm.batch_numbers || "")
      .split(/\r?\n|,/)
      .map((item) => item.trim())
      .filter(Boolean);
    if (!numbers.length) {
      showToast("Add one or more numbers in batch list.");
      return;
    }
    try {
      setDialingCall(true);
      const result = await fetchJson("/api/v1/hr/calls/batch", {
        method: "POST",
        body: JSON.stringify({
          phone_numbers: numbers,
          call_type: dialerForm.hr_call_type || "INTERVIEW",
          language: dialerForm.language,
          notes: dialerForm.hr_notes || null,
          scenario: buildScenarioPayload(),
        }),
      });
      const failed = result.failed || 0;
      showToast(`Batch created: ${result.created || 0} started, ${failed} failed.`);
      await loadEverything(true);
    } catch (error) {
      showToast(error.message || "Could not start HR batch calls.");
    } finally {
      setDialingCall(false);
    }
  }

  async function endCall(callSid) {
    if (!callSid) return;
    try {
      setEndingCallSid(callSid);
      const result = await fetchJson("/api/v1/calls/end", {
        method: "POST",
        body: JSON.stringify({ call_sid: callSid }),
      });
      setActiveDialSession((current) => {
        if (!current || current.call_sid !== callSid) return current;
        return { ...current, status: result.status };
      });
      showToast(result.ended ? "Call ended from admin panel." : "Call could not be ended.");
      await loadEverything(true);
    } catch (error) {
      showToast(error.message || "Could not end the call.");
    } finally {
      setEndingCallSid("");
    }
  }

  async function reloadKnowledge() {
    try {
      setRefreshing(true);
      const result = await fetchJson("/api/v1/training/reload", { method: "POST" });
      showToast(`Knowledge reloaded: ${result.chunks_loaded || result.chunksLoaded || 0} chunks ready.`);
      await loadEverything(true);
    } catch (error) {
      showToast(error.message || "Knowledge reload failed.");
      setRefreshing(false);
    }
  }

  async function connectExpert(callSid) {
    if (!callSid || !assistForm.expert_number.trim()) {
      showToast("Enter the expert phone number first.");
      return;
    }

    try {
      setConnectingExpert(true);
      const result = await fetchJson("/api/v1/calls/expert-connect", {
        method: "POST",
        body: JSON.stringify({
          call_sid: callSid,
          phone_number: assistForm.expert_number.trim(),
        }),
      });
      showToast(`Expert is being connected to conference ${result.conference_name}.`);
      await loadEverything(true);
    } catch (error) {
      showToast(error.message || "Could not connect the expert.");
    } finally {
      setConnectingExpert(false);
    }
  }

  async function connectMonitor(callSid) {
    if (!callSid || !assistForm.monitor_number.trim()) {
      showToast("Enter the listen-only phone number first.");
      return;
    }

    try {
      setConnectingMonitor(true);
      const result = await fetchJson("/api/v1/calls/monitor-connect", {
        method: "POST",
        body: JSON.stringify({
          call_sid: callSid,
          phone_number: assistForm.monitor_number.trim(),
        }),
      });
      showToast(`Listen-only monitor is being connected to conference ${result.conference_name}.`);
      await loadEverything(true);
    } catch (error) {
      showToast(error.message || "Could not connect the monitor line.");
    } finally {
      setConnectingMonitor(false);
    }
  }

  async function saveVoiceBackend(event) {
    event.preventDefault();
    try {
      setSavingVoiceBackend(true);
      const result = await fetchJson("/api/v1/system/voice-backend", {
        method: "POST",
        body: JSON.stringify(voiceBackendForm),
      });
      setState((current) => ({
        ...current,
        status: {
          ...(current.status || {}),
          selected_voice_backend: result.voice_backend,
          voice_backend_configured: result.configured,
          voice_backend_ready: result.ready,
          voice_backend_hint: result.hint,
          voice_backend_websocket_url: result.websocket_url || null,
        },
      }));
      showToast(`Voice backend set to ${titleCase(result.voice_backend)}.`);
    } catch (error) {
      showToast(error.message || "Could not save voice backend.");
    } finally {
      setSavingVoiceBackend(false);
    }
  }

  async function saveTtsProvider(event) {
    event.preventDefault();
    try {
      setSavingTtsProvider(true);
      const result = await fetchJson("/api/v1/system/tts-provider", {
        method: "POST",
        body: JSON.stringify(ttsProviderForm),
      });
      setState((current) => ({
        ...current,
        status: {
          ...(current.status || {}),
          selected_tts_provider: result.tts_provider,
          selected_piper_voice: result.selected_piper_voice,
          available_piper_voices: result.available_piper_voices,
          tts_configured: result.configured,
          tts_ready: result.ready,
          tts_hint: result.hint,
        },
      }));
      setTtsProviderForm((current) => ({
        ...current,
        tts_provider: result.tts_provider,
        piper_voice: result.selected_piper_voice || current.piper_voice,
      }));
      showToast(
        result.tts_provider === "piper"
          ? `Voice system set to Piper (${titleCase(result.selected_piper_voice || "amy")}).`
          : `Voice system set to ${titleCase(result.tts_provider)}.`
      );
    } catch (error) {
      showToast(error.message || "Could not save voice system.");
    } finally {
      setSavingTtsProvider(false);
    }
  }

  async function saveLlmSettings(event) {
    if (event) {
      event.preventDefault();
    }

    if (llmForm.llm_provider !== "local" && !llmForm.llm_model.trim()) {
      showToast("Enter an LLM model before saving.");
      return null;
    }

    try {
      setSavingLlmSettings(true);
      const payload = {
        llm_provider: llmForm.llm_provider,
        llm_model: llmForm.llm_model.trim(),
        llm_api_url: llmForm.llm_api_url.trim(),
        llm_api_format: llmForm.llm_api_format,
        llm_api_key: llmForm.llm_api_key.trim(),
      };

      if (llmForm.llm_provider === "local" && llmForm.llm_profile_id) {
        payload.llm_profile_id = llmForm.llm_profile_id;
      }

      const result = await fetchJson("/api/v1/system/llm", {
        method: "POST",
        body: JSON.stringify(payload),
      });
      setState((current) => ({
        ...current,
        status: {
          ...(current.status || {}),
          llm_provider: result.llm_provider,
          llm_model: result.llm_model,
          llm_api_url: result.llm_api_url,
          llm_api_format: result.llm_api_format,
          available_llm_providers: result.available_llm_providers,
          available_local_profiles: result.available_local_profiles || getValue(current, ["status", "available_local_profiles"], []),
          selected_local_profile_id: result.selected_local_profile_id,
          llm_configured: result.configured,
          llm_ready: result.ready,
          llm_hint: result.hint,
        },
      }));
      setLlmForm((current) => ({
        ...current,
        llm_api_key: "",
        llm_profile_id: result.selected_local_profile_id || "",
        llm_provider: result.llm_provider,
        llm_model: result.llm_model,
        llm_api_url: result.llm_api_url,
        llm_api_format: result.llm_api_format,
      }));
      showToast(`LLM set to ${result.llm_model}.`);
      return result;
    } catch (error) {
      showToast(error.message || "Could not save LLM settings.");
      return null;
    } finally {
      setSavingLlmSettings(false);
    }
  }

  function applyLlmProviderPreset(provider) {
    const preset = llmPresetForProvider(provider);
    setLlmForm((current) => ({
      ...current,
      llm_profile_id:
        provider === "local"
          ? getValue(state, ["status", "selected_local_profile_id"], "")
          : "",
      ...preset,
    }));
    setLlmTestResult(null);
  }

  function applyLocalProfile(profileId) {
    const profile = localProfileById(state.status, profileId);
    if (!profile) return;
    setLlmForm((current) => ({
      ...current,
      llm_profile_id: profile.id,
      llm_provider: "local",
      llm_model: profile.model,
      llm_api_url: profile.llm_api_url,
      llm_api_format: profile.llm_api_format,
    }));
    setLlmTestResult(null);
  }

  async function testLlmSettings(event) {
    event.preventDefault();
    if (!llmTestForm.prompt.trim()) {
      showToast("Enter a test prompt first.");
      return;
    }

    const saved = await saveLlmSettings();
    if (!saved) {
      return;
    }

    try {
      setTestingLlm(true);
      const result = await fetchJson("/api/v1/system/llm/test", {
        method: "POST",
        body: JSON.stringify({
          prompt: llmTestForm.prompt.trim(),
          max_tokens: Number(llmTestForm.max_tokens) || 120,
        }),
      });
      setLlmTestResult(result);
      showToast(`LLM test completed with ${result.llm_model}.`);
    } catch (error) {
      setLlmTestResult(null);
      showToast(error.message || "Could not run the LLM test.");
    } finally {
      setTestingLlm(false);
    }
  }

  async function submitTraining(event) {
    event.preventDefault();
    try {
      setSavingTraining(true);
      await fetchJson("/api/v1/training/text", {
        method: "POST",
        body: JSON.stringify({
          category: trainingForm.category,
          title: trainingForm.title,
          content: trainingForm.content,
        }),
      });
      setTrainingForm(initialTrainingForm);
      showToast("Training note added. Reloading knowledge base now.");
      await reloadKnowledge();
    } catch (error) {
      showToast(error.message || "Could not save training note.");
    } finally {
      setSavingTraining(false);
    }
  }

  async function approveFact(id) {
    try {
      await fetchJson(`/api/v1/training/facts/${encodeURIComponent(id)}`, {
        method: "PATCH",
        body: JSON.stringify({ approved: true }),
      });
      showToast("Learned fact approved.");
      await loadEverything(true);
    } catch (error) {
      showToast(error.message || "Could not approve fact.");
    }
  }

  function updateTrainingForm(key, value) {
    setTrainingForm((current) => ({ ...current, [key]: value }));
  }

  function updateDialerForm(key, value) {
    setDialerForm((current) => ({ ...current, [key]: value }));
  }

  function updateContactForm(key, value) {
    setContactForm((current) => ({ ...current, [key]: value }));
  }

  function updateAssistForm(key, value) {
    setAssistForm((current) => ({ ...current, [key]: value }));
  }

  function useHistoryCallForDialer(call) {
    const preferredNumber = call.direction === "inbound" ? call.from : call.to;
    setDialerForm((current) => ({
      ...current,
      to: preferredNumber || call.to || call.from || "",
      language: call.language || "hi-IN",
    }));
    setSection("dialer");
  }

  function useContactForDialer(contact) {
    setDialerForm((current) => ({
      ...current,
      to: contact.phone_number || "",
      language: contact.preferred_language || current.language || "hi-IN",
      contact_name: contact.name || "",
      contact_email: contact.email || "",
      contact_department: contact.department || "",
      contact_designation: contact.designation || "",
      contact_notes: contact.notes || "",
      contact_tags: (contact.tags || []).join(", "),
    }));
    setSection("hr");
  }

  function manageLiveCall(call) {
    setActiveDialSession({
      call_sid: call.call_sid,
      to: call.to,
      from: call.from,
      status: call.status,
      language: call.language,
      provider: getValue(state, ["status", "selected_voice_backend"], "twilio_native"),
    });
    setSelectedCall(call.call_sid);
    setSection("dialer");
  }

  function showToast(message) {
    setToast(message);
  }

  useEffect(() => {
    loadEverything(false);
    const timer = setInterval(() => loadEverything(true), 15000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(() => setToast(null), 3200);
    return () => clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    if (!activeDialSession || !activeDialSession.call_sid) {
      return undefined;
    }

    const matchingHistoryItem = (state.history || []).find((item) => item.call_sid === activeDialSession.call_sid);
    if (matchingHistoryItem) {
      setActiveDialSession((current) => {
        if (!current || current.call_sid !== matchingHistoryItem.call_sid) return current;
        const nextStatus = matchingHistoryItem.status || current.status;
        const nextTo = matchingHistoryItem.to || current.to;
        const nextFrom = matchingHistoryItem.from || current.from;
        const nextLanguage = matchingHistoryItem.language || current.language;
        if (
          current.status === nextStatus &&
          current.to === nextTo &&
          current.from === nextFrom &&
          current.language === nextLanguage
        ) {
          return current;
        }
        return {
          ...current,
          to: nextTo,
          from: nextFrom,
          status: nextStatus,
          language: nextLanguage,
        };
      });
    }
    return undefined;
  }, [state.history, activeDialSession ? activeDialSession.call_sid : ""]);

  useEffect(() => {
    if (!activeDialSession || !activeDialSession.call_sid || !isLiveCallStatus(activeDialSession.status)) {
      return undefined;
    }

    const timer = setInterval(async () => {
      try {
        const status = await fetchJson(`/api/v1/calls/${encodeURIComponent(activeDialSession.call_sid)}/status`);
        setActiveDialSession((current) => {
          if (!current || current.call_sid !== status.call_sid) return current;
          return { ...current, status: status.status };
        });
        await loadEverything(true);
      } catch (error) {
        // Keep polling silent. The main dashboard refresh will surface persistent failures.
      }
    }, 5000);

    return () => clearInterval(timer);
  }, [activeDialSession ? activeDialSession.call_sid : "", activeDialSession ? activeDialSession.status : ""]);

  const lastUpdated = new Date().toLocaleTimeString();

  if (loading) {
    return html`
      <div className="main" style=${{ minHeight: "100vh", placeItems: "center", display: "grid" }}>
        <div className="loading">Loading VoiceNova Admin</div>
      </div>
    `;
  }

  return html`
    <div className="admin-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">VN</div>
          <div className="brand-title">VoiceNova Admin</div>
          <div className="brand-subtitle">
           Ishant Sharma.
          </div>
        </div>

        <nav className="nav">
          ${sections.map((item) => html`
            <button
              key=${item.id}
              className=${`nav-button ${section === item.id ? "active" : ""}`}
              onClick=${() => setSection(item.id)}
            >
              <span className="nav-label">
                <span className="nav-icon">${item.icon}</span>
                <span>${item.label}</span>
              </span>
              <span className="badge badge-sky">
                ${item.id === "calls"
                  ? `${state.history.length}`
                    : item.id === "dialer"
                      ? `${activeDialSession && isLiveCallStatus(activeDialSession.status) ? 1 : 0}`
                    : item.id === "hr"
                      ? `${(state.hrCallHistory || []).length}`
                    : "Live"}
              </span>
            </button>
          `)}
        </nav>

        <div className="status-card">
          <div className="status-chip">
            <span className="pulse"></span>
            Backend ${getValue(state, ["health", "status"], "") === "ok" ? "online" : "degraded"}
          </div>
          <div className="brand-subtitle">
            AI mode: <strong>${titleCase(getValue(state, ["status", "ai_mode"], "unknown"))}</strong><br />
            Telephony: <strong>${getValue(state, ["status", "twilio_mock_mode"], false) ? "Mock" : "Live"}</strong><br />
            Updated at <strong>${lastUpdated}</strong>
          </div>
        </div>
      </aside>

      <main className="main">
        <section className="hero">
          <div>
            <div className="eyebrow">Operations Control</div>
            <h1>Premium admin panel for live call oversight and business training.</h1>
            <p>
              Review analytics, inspect every call session, reload knowledge instantly, approve learned facts, and
              monitor the exact prompt and response path when something feels off.
            </p>
          </div>
          <div className="hero-actions">
            <button className="button button-ghost" onClick=${() => loadEverything(true)}>
              ${refreshing ? "Refreshing..." : "Refresh Data"}
            </button>
            <button className="button button-primary" onClick=${reloadKnowledge}>
              Reload Knowledge
            </button>
          </div>
        </section>

        ${section === "overview" && html`
          <section className="grid-metrics">
            ${metrics.map((item) => html`
              <article key=${item.label} className="panel metric-card">
                <small>${item.label}</small>
                <div className="metric-value">${item.value}</div>
                <div className="metric-meta">${item.meta}</div>
                <div className="metric-bar">
                  <div className="metric-fill" style=${{ width: `${item.progress}%` }}></div>
                </div>
              </article>
            `)}
          </section>

          <section className="split-grid">
            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Live Snapshot</h2>
                  <div className="panel-subtitle">
                    Current agent mode, webhook posture, and the most recent business call activity.
                  </div>
                </div>
                <span className="badge badge-teal">${getValue(state, ["status", "auto_answer_inbound"], false) ? "Auto answer ready" : "Public webhook needed"}</span>
              </div>
              <div className="pill-row">
                <div className="pill"><strong>Model</strong> ${getValue(state, ["status", "llm_model"], "Unknown")}</div>
                <div className="pill"><strong>Provider</strong> ${titleCase(getValue(state, ["status", "llm_provider"], "unknown"))}</div>
                <div className="pill"><strong>Call controls</strong> ${titleCase(getValue(state, ["status", "call_controls_mode"], "unknown"))}</div>
                <div className="pill"><strong>Webhook</strong> ${getValue(state, ["status", "twilio_inbound_webhook"], "Unavailable")}</div>
              </div>
              <div className="source-row" style=${{ marginTop: "16px" }}>
                <strong>Inbound setup:</strong> ${getValue(state, ["status", "inbound_setup_hint"], "Connect a public Twilio webhook to answer inbound calls.")}
              </div>

              <div className="stack" style=${{ marginTop: "18px" }}>
                ${(activeCalls.length ? activeCalls : state.history.slice(0, 4)).map((call) => html`
                  <div key=${call.call_sid} className="call-row" onClick=${() => { setSection("calls"); loadCallDebug(call.call_sid); }}>
                    <div className="call-topline">
                      <div className="call-parties">${call.from} → ${call.to}</div>
                      <span className=${`badge ${statusBadgeClass(call.status)}`}>${titleCase(call.status)}</span>
                    </div>
                    <div className="call-id">${call.call_sid}</div>
                    <div className="call-meta">
                      <span>${titleCase(call.direction)}</span>
                      <span>${call.language}</span>
                      <span>${formatDuration(call.duration_seconds)}</span>
                      <span>${formatTime(call.started_at)}</span>
                    </div>
                  </div>
                `)}
                ${!state.history.length && html`<div className="empty">No call history yet. Once calls run through the backend, this room will fill automatically.</div>`}
              </div>
            </article>

            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Knowledge Coverage</h2>
                  <div className="panel-subtitle">
                    What the assistant is currently loaded with and where your customer training should go next.
                  </div>
                </div>
                <span className="badge badge-amber">${getValue(state, ["context", "documentsLoaded"], 0)} docs</span>
              </div>

              <div className="key-grid">
                <div className="key-tile">
                  <div className="key-label">Agent Name</div>
                  <div className="key-value">${getValue(state, ["context", "agentName"], "Unknown")}</div>
                </div>
                <div className="key-tile">
                  <div className="key-label">Company</div>
                  <div className="key-value">${getValue(state, ["context", "company"], "Unknown")}</div>
                </div>
                <div className="key-tile">
                  <div className="key-label">Chunks Loaded</div>
                  <div className="key-value">${getValue(state, ["context", "totalChunks"], 0)}</div>
                </div>
                <div className="key-tile">
                  <div className="key-label">Learned Facts</div>
                  <div className="key-value">${state.learnedFacts.length}</div>
                </div>
              </div>

              <div className="stack" style=${{ marginTop: "18px" }}>
                ${(getValue(state, ["context", "topicsCovered"], [])).slice(0, 10).map((topic, index) => html`
                  <div key=${index} className="source-row">${topic}</div>
                `)}
                ${!(getValue(state, ["context", "topicsCovered"], [])).length && html`<div className="empty">Add business files under <code>knowledge/</code> to populate this area.</div>`}
              </div>
            </article>
          </section>
        `}

        ${section === "dialer" && html`
          <section className="split-grid">
            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Admin Call Center</h2>
                  <div className="panel-subtitle">
                    Start outbound business calls directly from the admin panel using the same live Twilio flow as the mobile app.
                  </div>
                </div>
                <span className="badge badge-teal">${getValue(state, ["status", "call_controls_mode"], "unknown")}</span>
              </div>

              <form className="form-grid" onSubmit=${(event) => startOutboundCall(event, { hrMode: false })}>
                <div className="form-row">
                  <label htmlFor="dialer-number">Customer Number</label>
                  <input
                    id="dialer-number"
                    value=${dialerForm.to}
                    onChange=${(event) => updateDialerForm("to", event.target.value)}
                    placeholder="7976142577 or +917976142577"
                    required
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="dialer-language">Conversation Language</label>
                  <select
                    id="dialer-language"
                    value=${dialerForm.language}
                    onChange=${(event) => updateDialerForm("language", event.target.value)}
                  >
                    <option value="hi-IN">Hindi</option>
                    <option value="en-IN">English</option>
                    <option value="en-US">English (US)</option>
                  </select>
                </div>

                <div className="toolbar">
                  <button type="submit" className="button button-primary" disabled=${dialingCall}>
                    ${dialingCall ? "Starting Call..." : "Start Admin Call"}
                  </button>
                  <button
                    type="button"
                    className="button button-ghost"
                    onClick=${() => setDialerForm(initialDialerForm)}
                  >
                    Reset
                  </button>
                </div>
              </form>

              <div className="stack" style=${{ marginTop: "18px" }}>
                <div className="source-row">
                  The backend will normalize Indian 10-digit numbers to <strong>+91</strong> automatically before dialing.
                </div>
                <div className="source-row">
                  This is pure admin outbound dialing without HR tagging.
                </div>
              </div>
            </article>

            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Current Admin Call</h2>
                  <div className="panel-subtitle">
                    Live status, call SID, and a fast route into the debug inspector for the latest dialed session.
                  </div>
                </div>
                <span className=${`badge ${statusBadgeClass(activeDialSession ? activeDialSession.status : "unknown")}`}>
                  ${titleCase(activeDialSession ? activeDialSession.status : "idle")}
                </span>
              </div>

              ${activeDialSession && html`
                <div className="stack">
                  <div className="key-grid">
                    <div className="key-tile">
                      <div className="key-label">Dialed Number</div>
                      <div className="key-value">${activeDialSession.to || "Unavailable"}</div>
                    </div>
                    <div className="key-tile">
                      <div className="key-label">Call SID</div>
                      <div className="key-value">${activeDialSession.call_sid}</div>
                    </div>
                    <div className="key-tile">
                      <div className="key-label">Status</div>
                      <div className="key-value">${titleCase(activeDialSession.status)}</div>
                    </div>
                    <div className="key-tile">
                      <div className="key-label">Provider</div>
                      <div className="key-value">${activeDialSession.provider || "Twilio"}</div>
                    </div>
                  </div>

                  <div className="toolbar">
                    <button className="button button-ghost" onClick=${() => loadCallDebug(activeDialSession.call_sid)}>
                      Open Debug Trace
                    </button>
                    <button
                      className="button button-danger"
                      onClick=${() => endCall(activeDialSession.call_sid)}
                      disabled=${endingCallSid === activeDialSession.call_sid || !isLiveCallStatus(activeDialSession.status)}
                    >
                      ${endingCallSid === activeDialSession.call_sid ? "Ending..." : "End Call"}
                    </button>
                  </div>

                  <div className="stack" style=${{ marginTop: "18px" }}>
                    <div className="source-row">
                      Twilio supervised-call controls: move the caller to a live conference, then add an expert who can talk or a monitor line that can only listen.
                    </div>
                    <div className="source-row">
                      These controls work only when the voice backend is <strong>Twilio Native</strong>.
                    </div>
                  </div>

                  <div className="form-grid" style=${{ marginTop: "18px" }}>
                    <div className="form-row">
                      <label htmlFor="expert-number">Expert Number</label>
                      <input
                        id="expert-number"
                        value=${assistForm.expert_number}
                        onChange=${(event) => updateAssistForm("expert_number", event.target.value)}
                        placeholder="+919876543210"
                      />
                    </div>
                    <div className="toolbar">
                      <button
                        className="button button-primary"
                        onClick=${() => connectExpert(activeDialSession.call_sid)}
                        disabled=${connectingExpert || getValue(state, ["status", "selected_voice_backend"], "") !== "twilio_native"}
                      >
                        ${connectingExpert ? "Connecting Expert..." : "Connect Expert"}
                      </button>
                    </div>

                    <div className="form-row">
                      <label htmlFor="monitor-number">Listen-Only Number</label>
                      <input
                        id="monitor-number"
                        value=${assistForm.monitor_number}
                        onChange=${(event) => updateAssistForm("monitor_number", event.target.value)}
                        placeholder="+919876543210"
                      />
                    </div>
                    <div className="toolbar">
                      <button
                        className="button button-ghost"
                        onClick=${() => connectMonitor(activeDialSession.call_sid)}
                        disabled=${connectingMonitor || getValue(state, ["status", "selected_voice_backend"], "") !== "twilio_native"}
                      >
                        ${connectingMonitor ? "Connecting Listener..." : "Listen Only"}
                      </button>
                    </div>
                  </div>
                </div>
              `}

              ${!activeDialSession && html`
                <div className="empty">
                  Start a call from the left and this panel will show its live tracking details here.
                </div>
              `}
            </article>
          </section>

          <section className="panel">
            <div className="panel-header">
              <div>
                <h2 className="panel-title">Quick Dial From Recent Calls</h2>
                <div className="panel-subtitle">
                  Reuse numbers and languages from recent call history to speed up callbacks and follow-ups.
                </div>
              </div>
              <span className="badge badge-sky">${state.history.length} recent</span>
            </div>

            <div className="stack">
              ${(state.history || []).slice(0, 6).map((call) => html`
                <div key=${call.call_sid} className="call-row">
                  <div className="call-topline">
                    <div className="call-parties">${call.from} → ${call.to}</div>
                    <span className=${`badge ${statusBadgeClass(call.status)}`}>${titleCase(call.status)}</span>
                  </div>
                  <div className="call-meta">
                    <span>${titleCase(call.direction)}</span>
                    <span>${call.language}</span>
                    <span>${formatTime(call.started_at)}</span>
                  </div>
                  <div className="toolbar">
                    <button className="button button-ghost" onClick=${() => useHistoryCallForDialer(call)}>
                      Use In Dialer
                    </button>
                    ${call.status === "active" && html`
                      <button className="button button-ghost" onClick=${() => manageLiveCall(call)}>
                        Manage Live Call
                      </button>
                    `}
                    <button className="button button-ghost" onClick=${() => loadCallDebug(call.call_sid)}>
                      Open Debug
                    </button>
                  </div>
                </div>
              `)}
              ${!(state.history || []).length && html`
                <div className="empty">Recent outbound and inbound calls will appear here for one-click redial workflows.</div>
              `}
            </div>
          </section>
        `}

        ${section === "hr" && html`
          <section className="split-grid">
            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">HR Call Center</h2>
                  <div className="panel-subtitle">
                    Run interview, salary, attendance, and policy calls from a dedicated HR workflow.
                  </div>
                </div>
                <span className="badge badge-teal">${getValue(state, ["status", "call_controls_mode"], "unknown")}</span>
              </div>

              <form className="form-grid" onSubmit=${(event) => startOutboundCall(event, { hrMode: true })}>
                <div className="form-row">
                  <label htmlFor="hr-dialer-number">Candidate Number</label>
                  <input
                    id="hr-dialer-number"
                    value=${dialerForm.to}
                    onChange=${(event) => updateDialerForm("to", event.target.value)}
                    placeholder="7976142577 or +917976142577"
                    required
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="hr-dialer-language">Conversation Language</label>
                  <select
                    id="hr-dialer-language"
                    value=${dialerForm.language}
                    onChange=${(event) => updateDialerForm("language", event.target.value)}
                  >
                    <option value="hi-IN">Hindi</option>
                    <option value="en-IN">English</option>
                    <option value="en-US">English (US)</option>
                  </select>
                </div>

                <div className="form-row">
                  <label htmlFor="dialer-hr-type">HR Call Type</label>
                  <select
                    id="dialer-hr-type"
                    value=${dialerForm.hr_call_type}
                    onChange=${(event) => updateDialerForm("hr_call_type", event.target.value)}
                  >
                    <option value="INTERVIEW">Interview</option>
                    <option value="SALARY">Salary</option>
                    <option value="ATTENDANCE">Attendance</option>
                    <option value="PRIVACY_POLICY_CHANGED">Privacy Policy Changed</option>
                    <option value="CUSTOMER_SUPPORT">Customer Support</option>
                    <option value="OTHER">Other</option>
                  </select>
                </div>

                <div className="form-row">
                  <label htmlFor="hr-contact-name">Contact Name</label>
                  <input
                    id="hr-contact-name"
                    value=${dialerForm.contact_name}
                    onChange=${(event) => updateDialerForm("contact_name", event.target.value)}
                    placeholder="Rohit Sharma"
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="hr-contact-email">Contact Email</label>
                  <input
                    id="hr-contact-email"
                    value=${dialerForm.contact_email}
                    onChange=${(event) => updateDialerForm("contact_email", event.target.value)}
                    placeholder="name@example.com"
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="hr-contact-department">Department</label>
                  <input
                    id="hr-contact-department"
                    value=${dialerForm.contact_department}
                    onChange=${(event) => updateDialerForm("contact_department", event.target.value)}
                    placeholder="HR / Finance / Operations"
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="hr-contact-designation">Designation</label>
                  <input
                    id="hr-contact-designation"
                    value=${dialerForm.contact_designation}
                    onChange=${(event) => updateDialerForm("contact_designation", event.target.value)}
                    placeholder="Candidate / Employee / Manager"
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="hr-contact-tags">Tags</label>
                  <input
                    id="hr-contact-tags"
                    value=${dialerForm.contact_tags}
                    onChange=${(event) => updateDialerForm("contact_tags", event.target.value)}
                    placeholder="engineering, payroll, follow-up"
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="dialer-hr-notes">HR Notes</label>
                  <input
                    id="dialer-hr-notes"
                    value=${dialerForm.hr_notes}
                    onChange=${(event) => updateDialerForm("hr_notes", event.target.value)}
                    placeholder="Optional notes saved with call history"
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="hr-contact-notes">Contact Notes</label>
                  <input
                    id="hr-contact-notes"
                    value=${dialerForm.contact_notes}
                    onChange=${(event) => updateDialerForm("contact_notes", event.target.value)}
                    placeholder="Known preferences or previous discussion"
                  />
                </div>

                ${dialerForm.hr_call_type === "INTERVIEW" && html`
                  <div className="form-row">
                    <label htmlFor="hr-interview-round">Interview Round</label>
                    <input
                      id="hr-interview-round"
                      value=${dialerForm.interview_round}
                      onChange=${(event) => updateDialerForm("interview_round", event.target.value)}
                      placeholder="Round 1 / Technical / Final"
                    />
                  </div>
                  <div className="form-row">
                    <label htmlFor="hr-interview-details">Interview Details</label>
                    <textarea
                      id="hr-interview-details"
                      value=${dialerForm.interview_details}
                      onChange=${(event) => updateDialerForm("interview_details", event.target.value)}
                      placeholder="Role, expectations, or round-specific instructions"
                    ></textarea>
                  </div>
                `}

                ${dialerForm.hr_call_type === "SALARY" && html`
                  <div className="form-row">
                    <label htmlFor="hr-salary-credited">Salary Credited</label>
                    <select
                      id="hr-salary-credited"
                      value=${dialerForm.salary_credited}
                      onChange=${(event) => updateDialerForm("salary_credited", event.target.value)}
                    >
                      <option value="yes">Credited</option>
                      <option value="no">Not Credited</option>
                    </select>
                  </div>
                  <div className="form-row">
                    <label htmlFor="hr-salary-amount">Salary Amount</label>
                    <input
                      id="hr-salary-amount"
                      value=${dialerForm.salary_amount}
                      onChange=${(event) => updateDialerForm("salary_amount", event.target.value)}
                      placeholder="₹45,000"
                    />
                  </div>
                `}

                ${dialerForm.hr_call_type === "ATTENDANCE" && html`
                  <div className="form-row">
                    <label htmlFor="hr-attendance-date">Attendance Date</label>
                    <input
                      id="hr-attendance-date"
                      type="date"
                      value=${dialerForm.attendance_date}
                      onChange=${(event) => updateDialerForm("attendance_date", event.target.value)}
                    />
                  </div>
                `}

                ${dialerForm.hr_call_type === "PRIVACY_POLICY_CHANGED" && html`
                  <div className="form-row">
                    <label htmlFor="hr-privacy-summary">What Changed</label>
                    <textarea
                      id="hr-privacy-summary"
                      value=${dialerForm.privacy_change_summary}
                      onChange=${(event) => updateDialerForm("privacy_change_summary", event.target.value)}
                      placeholder="Briefly describe what changed in the privacy policy"
                    ></textarea>
                  </div>
                `}

                ${(dialerForm.hr_call_type === "OTHER" || dialerForm.hr_call_type === "CUSTOMER_SUPPORT") && html`
                  <div className="form-row">
                    <label htmlFor="faq-q1">FAQ 1 Question</label>
                    <input
                      id="faq-q1"
                      value=${dialerForm.other_faq_question_1}
                      onChange=${(event) => updateDialerForm("other_faq_question_1", event.target.value)}
                      placeholder="What is the new joining date?"
                    />
                  </div>
                  <div className="form-row">
                    <label htmlFor="faq-a1">FAQ 1 Answer</label>
                    <textarea
                      id="faq-a1"
                      value=${dialerForm.other_faq_answer_1}
                      onChange=${(event) => updateDialerForm("other_faq_answer_1", event.target.value)}
                      placeholder="The joining date has moved to..."
                    ></textarea>
                  </div>
                  <div className="form-row">
                    <label htmlFor="faq-q2">FAQ 2 Question</label>
                    <input
                      id="faq-q2"
                      value=${dialerForm.other_faq_question_2}
                      onChange=${(event) => updateDialerForm("other_faq_question_2", event.target.value)}
                      placeholder="Add another common question"
                    />
                  </div>
                  <div className="form-row">
                    <label htmlFor="faq-a2">FAQ 2 Answer</label>
                    <textarea
                      id="faq-a2"
                      value=${dialerForm.other_faq_answer_2}
                      onChange=${(event) => updateDialerForm("other_faq_answer_2", event.target.value)}
                      placeholder="Add another answer"
                    ></textarea>
                  </div>
                `}

                <div className="toolbar">
                  <button type="submit" className="button button-primary" disabled=${dialingCall}>
                    ${dialingCall ? "Starting Call..." : "Start Single HR Call"}
                  </button>
                  <button type="button" className="button button-ghost" onClick=${() => setDialerForm(initialDialerForm)}>
                    Reset
                  </button>
                </div>
              </form>

              <form className="form-grid" style=${{ marginTop: "18px" }} onSubmit=${startHrBatchCalls}>
                <div className="form-row">
                  <label htmlFor="dialer-batch-numbers">Batch Numbers (comma or new line)</label>
                  <textarea
                    id="dialer-batch-numbers"
                    value=${dialerForm.batch_numbers}
                    onChange=${(event) => updateDialerForm("batch_numbers", event.target.value)}
                    placeholder="+917900000001, +917900000002"
                  ></textarea>
                </div>
                <div className="toolbar">
                  <button type="submit" className="button button-primary" disabled=${dialingCall}>
                    ${dialingCall ? "Starting Batch..." : "Start Batch HR Calls"}
                  </button>
                </div>
              </form>
            </article>

            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">HR Call History</h2>
                  <div className="panel-subtitle">
                    Separate HR call logs with call type, date filters, notes, and batch id.
                  </div>
                </div>
                <span className="badge badge-teal">${(state.hrCallHistory || []).length} calls</span>
              </div>

              <div className="form-grid" style=${{ marginBottom: "12px" }}>
                <div className="form-row">
                  <label htmlFor="hr-call-type">Call Type</label>
                  <select
                    id="hr-call-type"
                    value=${hrCallFilter.call_type}
                    onChange=${(event) => setHrCallFilter({ ...hrCallFilter, call_type: event.target.value })}
                  >
                    <option value="">All</option>
                    <option value="INTERVIEW">Interview</option>
                    <option value="SALARY">Salary</option>
                    <option value="ATTENDANCE">Attendance</option>
                    <option value="PRIVACY_POLICY_CHANGED">Privacy Policy Changed</option>
                    <option value="CUSTOMER_SUPPORT">Customer Support</option>
                    <option value="OTHER">Other</option>
                  </select>
                </div>
                <div className="form-row">
                  <label htmlFor="hr-call-date-from">Date From</label>
                  <input
                    id="hr-call-date-from"
                    type="date"
                    value=${hrCallFilter.date_from}
                    onChange=${(event) => setHrCallFilter({ ...hrCallFilter, date_from: event.target.value })}
                  />
                </div>
                <div className="form-row">
                  <label htmlFor="hr-call-date-to">Date To</label>
                  <input
                    id="hr-call-date-to"
                    type="date"
                    value=${hrCallFilter.date_to}
                    onChange=${(event) => setHrCallFilter({ ...hrCallFilter, date_to: event.target.value })}
                  />
                </div>
                <div className="toolbar">
                  <button className="button button-primary" onClick=${loadHrCallsWithFilter} disabled=${loadingHrCalls}>
                    ${loadingHrCalls ? "Loading..." : "Apply Filter"}
                  </button>
                </div>
              </div>

              <div className="stack">
                ${(state.hrCallHistory || []).map((callItem) => html`
                  <div key=${callItem.call_sid + callItem.phone_number} className="call-row">
                    <div className="call-topline">
                      <div className="call-parties">${callItem.phone_number}</div>
                      <span className=${`badge ${statusBadgeClass(callItem.status)}`}>${titleCase(callItem.status || "unknown")}</span>
                    </div>
                    <div className="call-meta">
                      <span>Type: ${titleCase((callItem.call_type || "OTHER").replaceAll("_", " ").toLowerCase())}</span>
                      <span>Call SID: ${callItem.call_sid || "—"}</span>
                      <span>Batch: ${callItem.batch_id || "single"}</span>
                      <span>Created: ${formatTime(callItem.created_at)}</span>
                      <span>Updated: ${formatTime(callItem.updated_at)}</span>
                    </div>
                    <div className="source-row"><strong>Notes:</strong> ${callItem.notes || "—"}</div>
                  </div>
                `)}
                ${!(state.hrCallHistory || []).length && html`<div className="empty">No HR call records for this filter.</div>`}
              </div>
            </article>
          </section>

          <section className="panel" style=${{ marginTop: "16px" }}>
            <div className="panel-header">
              <div>
                <h2 className="panel-title">HR Candidates</h2>
                <div className="panel-subtitle">
                  Saved candidate details from HR call flow. Filter date-wise and review latest updates.
                </div>
              </div>
              <span className="badge badge-sky">${(state.hrCandidates || []).length} records</span>
            </div>

            <div className="form-grid" style=${{ marginBottom: "12px" }}>
              <div className="form-row">
                <label htmlFor="hr-date-from">Date From</label>
                <input
                  id="hr-date-from"
                  type="date"
                  value=${hrFilter.date_from}
                  onChange=${(event) => setHrFilter({ ...hrFilter, date_from: event.target.value })}
                />
              </div>
              <div className="form-row">
                <label htmlFor="hr-date-to">Date To</label>
                <input
                  id="hr-date-to"
                  type="date"
                  value=${hrFilter.date_to}
                  onChange=${(event) => setHrFilter({ ...hrFilter, date_to: event.target.value })}
                />
              </div>
              <div className="toolbar">
                <button className="button button-primary" onClick=${loadHrCandidatesWithFilter} disabled=${loadingHrCandidates}>
                  ${loadingHrCandidates ? "Loading..." : "Apply Filter"}
                </button>
              </div>
            </div>

            <div className="stack">
              ${(state.hrCandidates || []).map((candidate) => html`
                <div key=${candidate.phone_number} className="call-row">
                  <div className="call-topline">
                    <div className="call-parties">${candidate.name || "Unknown"} · ${candidate.phone_number}</div>
                    <div style=${{display: 'flex', gap: '10px', alignItems: 'center'}}>
                      <span className=${`badge ${candidate.consent_to_store ? "badge-green" : "badge-amber"}`}>
                        ${candidate.consent_to_store ? "Consent Yes" : "Consent No"}
                      </span>
                      <button className="btn btn-ghost" style=${{color: 'var(--red)', fontSize: '12px', padding: '4px 8px'}} onClick=${() => deleteHrCandidate(candidate.phone_number)}>Delete</button>
                    </div>
                  </div>
                  <div className="call-meta">
                    <span>Email: ${candidate.email || "—"}</span>
                    <span>Company: ${candidate.previous_company || "—"}</span>
                    <span>Role: ${candidate.desired_role || "—"}</span>
                    <span>Created: ${formatTime(candidate.created_at)}</span>
                    <span>Updated: ${formatTime(candidate.last_updated_at)}</span>
                  </div>
                  <div className="source-row"><strong>Self Intro:</strong> ${candidate.self_introduction || "—"}</div>
                  <div className="source-row"><strong>Projects:</strong> ${candidate.projects || "—"}</div>
                  <div className="source-row"><strong>Basic Q1 Answer:</strong> ${candidate.basic_answer_1 || "—"}</div>
                  <div className="source-row"><strong>Basic Q2 Answer:</strong> ${candidate.basic_answer_2 || "—"}</div>
                </div>
              `)}
              ${!(state.hrCandidates || []).length && html`<div className="empty">No HR candidate records for this date range.</div>`}
            </div>
          </section>
        `}

        ${section === "contacts" && html`
          <section className="split-grid">
            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Contact Details</h2>
                  <div className="panel-subtitle">
                    Save reusable people records so repeat calls greet by name and carry forward known context.
                  </div>
                </div>
                <span className="badge badge-teal">${(state.contacts || []).length} contacts</span>
              </div>

              <form className="form-grid" onSubmit=${saveContact}>
                <div className="form-row">
                  <label htmlFor="contact-phone">Phone Number</label>
                  <input id="contact-phone" value=${contactForm.phone_number} onChange=${(event) => updateContactForm("phone_number", event.target.value)} placeholder="+919876543210" required />
                </div>
                <div className="form-row">
                  <label htmlFor="contact-name">Name</label>
                  <input id="contact-name" value=${contactForm.name} onChange=${(event) => updateContactForm("name", event.target.value)} placeholder="Employee or candidate name" />
                </div>
                <div className="form-row">
                  <label htmlFor="contact-email">Email</label>
                  <input id="contact-email" value=${contactForm.email} onChange=${(event) => updateContactForm("email", event.target.value)} placeholder="name@example.com" />
                </div>
                <div className="form-row">
                  <label htmlFor="contact-department">Department</label>
                  <input id="contact-department" value=${contactForm.department} onChange=${(event) => updateContactForm("department", event.target.value)} placeholder="HR / Finance / Operations" />
                </div>
                <div className="form-row">
                  <label htmlFor="contact-designation">Designation</label>
                  <input id="contact-designation" value=${contactForm.designation} onChange=${(event) => updateContactForm("designation", event.target.value)} placeholder="Candidate / Employee / Manager" />
                </div>
                <div className="form-row">
                  <label htmlFor="contact-language">Preferred Language</label>
                  <select id="contact-language" value=${contactForm.preferred_language} onChange=${(event) => updateContactForm("preferred_language", event.target.value)}>
                    <option value="hi-IN">Hindi</option>
                    <option value="en-IN">English</option>
                    <option value="en-US">English (US)</option>
                  </select>
                </div>
                <div className="form-row">
                  <label htmlFor="contact-company">Company</label>
                  <input id="contact-company" value=${contactForm.company} onChange=${(event) => updateContactForm("company", event.target.value)} placeholder="Optional company name" />
                </div>
                <div className="form-row">
                  <label htmlFor="contact-tags">Tags</label>
                  <input id="contact-tags" value=${contactForm.tags} onChange=${(event) => updateContactForm("tags", event.target.value)} placeholder="payroll, interview, follow-up" />
                </div>
                <div className="form-row">
                  <label htmlFor="contact-notes">Notes</label>
                  <textarea id="contact-notes" value=${contactForm.notes} onChange=${(event) => updateContactForm("notes", event.target.value)} placeholder="Anything useful for future calls"></textarea>
                </div>
                <div className="toolbar">
                  <button type="submit" className="button button-primary" disabled=${savingContact}>
                    ${savingContact ? "Saving..." : "Save Contact"}
                  </button>
                  <button type="button" className="button button-ghost" onClick=${() => setContactForm(initialContactForm)}>
                    Reset
                  </button>
                </div>
              </form>
            </article>

            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Saved Contacts</h2>
                  <div className="panel-subtitle">
                    Use a saved contact directly in the HR dialer or remove outdated records.
                  </div>
                </div>
                <span className="badge badge-sky">${(state.contacts || []).length} saved</span>
              </div>

              <div className="stack">
                ${(state.contacts || []).map((contact) => html`
                  <div key=${contact.phone_number} className="call-row">
                    <div className="call-topline">
                      <div className="call-parties">${contact.name || "Unnamed Contact"} · ${contact.phone_number}</div>
                      <span className="badge badge-teal">${contact.preferred_language || "hi-IN"}</span>
                    </div>
                    <div className="call-meta">
                      <span>Email: ${contact.email || "—"}</span>
                      <span>Department: ${contact.department || "—"}</span>
                      <span>Designation: ${contact.designation || "—"}</span>
                      <span>Updated: ${formatTime(contact.last_updated_at)}</span>
                    </div>
                    <div className="source-row"><strong>Notes:</strong> ${contact.notes || "—"}</div>
                    <div className="source-row"><strong>Last Call:</strong> ${contact.last_call_summary || "—"}</div>
                    <div className="source-row"><strong>Last Attendance:</strong> ${contact.last_attendance_status || "—"}</div>
                    <div className="toolbar">
                      <button className="button button-ghost" onClick=${() => useContactForDialer(contact)}>
                        Use In HR Dialer
                      </button>
                      <button className="button button-ghost" onClick=${() => setContactForm({
                        phone_number: contact.phone_number || "",
                        name: contact.name || "",
                        email: contact.email || "",
                        department: contact.department || "",
                        designation: contact.designation || "",
                        preferred_language: contact.preferred_language || "hi-IN",
                        company: contact.company || "",
                        notes: contact.notes || "",
                        tags: (contact.tags || []).join(", "),
                      })}>
                        Edit
                      </button>
                      <button className="button button-danger" onClick=${() => deleteContact(contact.phone_number)}>
                        Delete
                      </button>
                    </div>
                  </div>
                `)}
                ${!(state.contacts || []).length && html`<div className="empty">Saved contacts will appear here after you add them or after the system learns from calls.</div>`}
              </div>
            </article>
          </section>
        `}

        ${section === "calls" && html`
          <section className="split-grid">
            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Call Monitoring</h2>
                  <div className="panel-subtitle">
                    Every tracked call session with direct access to detailed debug traces and recent transcripts.
                  </div>
                </div>
                <span className="badge badge-sky">${state.history.length} tracked calls</span>
              </div>

              <div className="stack">
                ${state.history.map((call) => html`
                  <button key=${call.call_sid} className="call-row" onClick=${() => loadCallDebug(call.call_sid)}>
                    <div className="call-topline">
                      <div className="call-parties">${call.from} → ${call.to}</div>
                      <span className=${`badge ${statusBadgeClass(call.status)}`}>${titleCase(call.status)}</span>
                    </div>
                    <div className="call-id">${call.call_sid}</div>
                    <div className="call-meta">
                      <span>${titleCase(call.direction)}</span>
                      <span>${call.language}</span>
                      <span>${formatDuration(call.duration_seconds)}</span>
                      <span>Started ${formatTime(call.started_at)}</span>
                    </div>
                  </button>
                `)}
                ${!state.history.length && html`<div className="empty">No calls are stored yet.</div>`}
              </div>
            </article>

            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Call Debug Inspector</h2>
                  <div className="panel-subtitle">
                    Open a call to inspect language detection, prompt building, LLM output, and Twilio lifecycle events.
                  </div>
                </div>
                ${selectedCall && html`<span className="badge badge-teal">${selectedCall}</span>`}
              </div>

              ${callDebugLoading && html`<div className="loading">Loading call debug</div>`}

              ${!callDebugLoading && callDebug && html`
                <div className="stack">
                  <div className="pill-row">
                    <div className="pill"><strong>Status</strong> ${titleCase(callDebug.status)}</div>
                    <div className="pill"><strong>Language</strong> ${callDebug.language}</div>
                    <div className="pill"><strong>Events</strong> ${callDebug.events.length}</div>
                  </div>

                  <div className="stack">
                    ${(callDebug.recent_transcript || []).map((line, index) => html`
                      <div key=${index} className="source-row">${line}</div>
                    `)}
                  </div>

                  <div className="list">
                    ${(callDebug.events || []).map((event, index) => html`
                      <div key=${index} className="event-row">
                        <div className="event-stage-row">
                          <div className="event-stage">${event.stage}</div>
                          <div className="event-message">${event.message}</div>
                        </div>
                        <div className="event-details">
                          <div><strong>Timestamp:</strong> ${formatTime(event.timestamp)}</div>
                          ${Object.entries(event.details || {}).map(([key, value]) => html`
                            <div key=${key} className="event-detail-block">
                              <strong>${titleCase(key)}:</strong>
                              ${isLongValue(value)
                                ? html`<pre className="event-detail-pre">${value || "—"}</pre>`
                                : html`<span className="event-detail-inline">${value || "—"}</span>`}
                            </div>
                          `)}
                        </div>
                      </div>
                    `)}
                  </div>
                </div>
              `}

              ${!callDebugLoading && !callDebug && html`
                <div className="empty">
                  Select any call on the left to load its debug details.
                </div>
              `}
            </article>
          </section>
        `}

        ${section === "training" && html`
          <section className="split-grid">
            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Add Training Content</h2>
                  <div className="panel-subtitle">
                    Drop business facts straight into the running knowledge base without touching code.
                  </div>
                </div>
                <span className="badge badge-green">Hot reload supported</span>
              </div>

              <form className="form-grid" onSubmit=${submitTraining}>
                <div className="form-row">
                  <label htmlFor="category">Category</label>
                  <select id="category" value=${trainingForm.category} onChange=${(event) => updateTrainingForm("category", event.target.value)}>
                    <option value="company">Company</option>
                    <option value="products">Products</option>
                    <option value="faq">FAQ</option>
                    <option value="scripts">Scripts</option>
                    <option value="general">General</option>
                  </select>
                </div>

                <div className="form-row">
                  <label htmlFor="title">Title</label>
                  <input
                    id="title"
                    value=${trainingForm.title}
                    onChange=${(event) => updateTrainingForm("title", event.target.value)}
                    placeholder="Example: Refund policy"
                    required
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="content">Content</label>
                  <textarea
                    id="content"
                    value=${trainingForm.content}
                    onChange=${(event) => updateTrainingForm("content", event.target.value)}
                    placeholder="Use Q/A, policy, or script format. Keep it factual."
                    required
                  ></textarea>
                </div>

                <div className="toolbar">
                  <button type="submit" className="button button-primary" disabled=${savingTraining}>
                    ${savingTraining ? "Saving..." : "Add Training"}
                  </button>
                  <button type="button" className="button button-ghost" onClick=${reloadKnowledge}>
                    Reload Knowledge
                  </button>
                </div>
              </form>
            </article>

            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Learned Facts Review</h2>
                  <div className="panel-subtitle">
                    Approve useful facts extracted from successful calls so the agent can reuse them later.
                  </div>
                </div>
                <span className="badge badge-amber">${state.learnedFacts.length} pending facts</span>
              </div>

              <div className="stack">
                ${state.learnedFacts.map((fact) => html`
                  <div key=${fact.id} className="fact-row">
                    <div className="eyebrow">${fact.language} · ${fact.id}</div>
                    <div className="event-message" style=${{ marginTop: "10px" }}>${fact.question}</div>
                    <div className="panel-subtitle">${fact.answer}</div>
                    <div className="toolbar" style=${{ marginTop: "12px" }}>
                      <button className="button button-primary" onClick=${() => approveFact(fact.id)}>
                        Approve Fact
                      </button>
                    </div>
                  </div>
                `)}
                ${!state.learnedFacts.length && html`
                  <div className="empty">
                    No learned facts are waiting right now. Once highly rated calls finish, extracted answers will show up here.
                  </div>
                `}
              </div>
            </article>
          </section>

        `}

        ${section === "system" && html`
          <section className="split-grid">
            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">LLM Control</h2>
                  <div className="panel-subtitle">
                    Switch the active language model at runtime and run a quick test before using it on live calls.
                  </div>
                </div>
                <span className=${`badge ${getValue(state, ["status", "llm_ready"], false) ? "badge-green" : "badge-amber"}`}>
                  ${getValue(state, ["status", "llm_ready"], false) ? "Ready" : "Needs setup"}
                </span>
              </div>

              <form className="form-grid" onSubmit=${saveLlmSettings}>
                <div className="form-row">
                  <label htmlFor="llm-provider">Provider</label>
                  <select
                    id="llm-provider"
                    value=${llmForm.llm_provider}
                    onChange=${(event) => applyLlmProviderPreset(event.target.value)}
                  >
                    ${getValue(state, ["status", "available_llm_providers"], ["local", "openai", "anthropic"]).map((provider) => html`
                      <option key=${provider} value=${provider}>${titleCase(provider)}</option>
                    `)}
                  </select>
                </div>

                ${llmForm.llm_provider === "local" && getValue(state, ["status", "available_local_profiles"], []).length > 0 && html`
                  <div className="form-row">
                    <label htmlFor="llm-local-profile">Local model</label>
                    <select
                      id="llm-local-profile"
                      value=${llmForm.llm_profile_id || getValue(state, ["status", "selected_local_profile_id"], "")}
                      onChange=${(event) => applyLocalProfile(event.target.value)}
                    >
                      ${getValue(state, ["status", "available_local_profiles"], []).map((profile) => html`
                        <option key=${profile.id} value=${profile.id}>
                          ${profile.label}
                        </option>
                      `)}
                    </select>
                  </div>
                `}

                <div className="form-row">
                  <label htmlFor="llm-model">Model</label>
                  <input
                    id="llm-model"
                    value=${llmForm.llm_model}
                    onChange=${(event) => setLlmForm({ ...llmForm, llm_model: event.target.value })}
                    placeholder="gpt-4o-mini"
                    required
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="llm-api-url">API URL</label>
                  <input
                    id="llm-api-url"
                    value=${llmForm.llm_api_url}
                    onChange=${(event) => setLlmForm({ ...llmForm, llm_api_url: event.target.value })}
                    placeholder="https://api.openai.com/v1/chat/completions"
                  />
                </div>

                <div className="form-row">
                  <label htmlFor="llm-api-format">API format</label>
                  <select
                    id="llm-api-format"
                    value=${llmForm.llm_api_format}
                    onChange=${(event) => setLlmForm({ ...llmForm, llm_api_format: event.target.value })}
                  >
                    <option value="auto">Auto</option>
                    <option value="openai">OpenAI-compatible</option>
                    <option value="ollama">Ollama</option>
                  </select>
                </div>

                <div className="form-row">
                  <label htmlFor="llm-api-key">API key</label>
                  <input
                    id="llm-api-key"
                    type="password"
                    value=${llmForm.llm_api_key}
                    onChange=${(event) => setLlmForm({ ...llmForm, llm_api_key: event.target.value })}
                    placeholder="Optional: leave blank to keep the current key"
                  />
                </div>

                <div className="key-grid">
                  <div className="key-tile">
                    <div className="key-label">Configured</div>
                    <div className="key-value">${getValue(state, ["status", "llm_configured"], false) ? "Yes" : "No"}</div>
                  </div>
                  <div className="key-tile">
                    <div className="key-label">Ready</div>
                    <div className="key-value">${getValue(state, ["status", "llm_ready"], false) ? "Yes" : "No"}</div>
                  </div>
                </div>

                <div className="source-row"><strong>Current model:</strong> ${getValue(state, ["status", "llm_model"], "Unknown")}</div>
                <div className="source-row"><strong>Current provider:</strong> ${titleCase(getValue(state, ["status", "llm_provider"], "unknown"))}</div>
                ${llmForm.llm_provider === "local" && llmForm.llm_profile_id && localProfileById(state.status, llmForm.llm_profile_id) && html`
                  <div className="source-row">
                    <strong>Selected local preset:</strong> ${localProfileById(state.status, llmForm.llm_profile_id).label}
                  </div>
                `}
                <div className="source-row"><strong>Scope:</strong> Saved to runtime storage. Restart keeps the latest saved settings unless you change startup config later.</div>
                <div className="source-row"><strong>Initial default:</strong> Local Ollama with <code>gemma:2b</code>.</div>
                <div className="source-row"><strong>Hint:</strong> ${getValue(state, ["status", "llm_hint"], "No model notes available.")}</div>

                <div className="toolbar">
                  <button type="submit" className="button button-primary" disabled=${savingLlmSettings || testingLlm}>
                    ${savingLlmSettings ? "Saving..." : "Save LLM"}
                  </button>
                </div>
              </form>

              <form className="form-grid" onSubmit=${testLlmSettings} style=${{ marginTop: "18px" }}>
                <div className="form-row">
                  <label htmlFor="llm-test-prompt">Test prompt</label>
                  <textarea
                    id="llm-test-prompt"
                    rows="4"
                    value=${llmTestForm.prompt}
                    onChange=${(event) => setLlmTestForm({ ...llmTestForm, prompt: event.target.value })}
                    placeholder="Ask the model to answer in one short sentence."
                  ></textarea>
                </div>

                <div className="form-row">
                  <label htmlFor="llm-test-tokens">Max tokens</label>
                  <input
                    id="llm-test-tokens"
                    type="number"
                    min="32"
                    max="512"
                    value=${llmTestForm.max_tokens}
                    onChange=${(event) => setLlmTestForm({ ...llmTestForm, max_tokens: event.target.value })}
                  />
                </div>

                <div className="toolbar">
                  <button type="submit" className="button button-primary" disabled=${testingLlm || savingLlmSettings}>
                    ${testingLlm ? "Testing..." : "Save And Test LLM"}
                  </button>
                </div>
              </form>

              ${llmTestResult && html`
                <div className="stack" style=${{ marginTop: "18px" }}>
                  <div className="source-row"><strong>Tested model:</strong> ${llmTestResult.llm_model}</div>
                  <div className="source-row"><strong>Provider:</strong> ${titleCase(llmTestResult.llm_provider)}</div>
                  <div className="source-row"><strong>Reply:</strong> ${llmTestResult.reply}</div>
                </div>
              `}
            </article>

            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Voice System</h2>
                  <div className="panel-subtitle">
                    Choose which speech voice callers hear during the call.
                  </div>
                </div>
                <span className=${`badge ${getValue(state, ["status", "tts_ready"], false) ? "badge-green" : "badge-amber"}`}>
                  ${getValue(state, ["status", "tts_ready"], false) ? "Ready" : "Needs setup"}
                </span>
              </div>

              <form className="form-grid" onSubmit=${saveTtsProvider}>
                <div className="form-row">
                  <label htmlFor="tts-provider">Voice system</label>
                  <select
                    id="tts-provider"
                    value=${ttsProviderForm.tts_provider}
                    onChange=${(event) =>
                      setTtsProviderForm((current) => ({
                        ...current,
                        tts_provider: event.target.value,
                      }))}
                  >
                    ${getValue(state, ["status", "available_tts_providers"], ["elevenlabs", "twilio_basic"]).map((provider) => html`
                      <option key=${provider} value=${provider}>${titleCase(provider)}</option>
                    `)}
                  </select>
                </div>

                ${ttsProviderForm.tts_provider === "piper" ? html`
                  <div className="form-row">
                    <label htmlFor="piper-voice">Piper voice</label>
                    <select
                      id="piper-voice"
                      value=${ttsProviderForm.piper_voice}
                      onChange=${(event) =>
                        setTtsProviderForm((current) => ({
                          ...current,
                          piper_voice: event.target.value,
                        }))}
                    >
                      ${getValue(state, ["status", "available_piper_voices"], []).map((voice) => html`
                        <option key=${voice.id} value=${voice.id}>
                          ${voice.label} (${titleCase(voice.gender)})
                        </option>
                      `)}
                    </select>
                  </div>
                ` : null}

                <div className="key-grid">
                  <div className="key-tile">
                    <div className="key-label">Configured</div>
                    <div className="key-value">${getValue(state, ["status", "tts_configured"], false) ? "Yes" : "No"}</div>
                  </div>
                  <div className="key-tile">
                    <div className="key-label">Ready</div>
                    <div className="key-value">${getValue(state, ["status", "tts_ready"], false) ? "Yes" : "No"}</div>
                  </div>
                </div>

                <div className="source-row"><strong>Current voice system:</strong> ${titleCase(getValue(state, ["status", "selected_tts_provider"], "elevenlabs"))}</div>
                <div className="source-row"><strong>Piper voice:</strong> ${titleCase(getValue(state, ["status", "selected_piper_voice"], "amy"))}</div>
                <div className="source-row"><strong>Hint:</strong> ${getValue(state, ["status", "tts_hint"], "No voice system notes available.")}</div>

                <div className="toolbar">
                  <button type="submit" className="button button-primary" disabled=${savingTtsProvider}>
                    ${savingTtsProvider ? "Saving..." : "Save Voice System"}
                  </button>
                </div>
              </form>
            </article>

            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Voice Backend</h2>
                  <div className="panel-subtitle">
                    Choose the voice orchestration path the admin should target for calls.
                  </div>
                </div>
                <span className=${`badge ${getValue(state, ["status", "voice_backend_ready"], false) ? "badge-green" : "badge-amber"}`}>
                  ${getValue(state, ["status", "voice_backend_ready"], false) ? "Ready" : "Needs bridge"}
                </span>
              </div>

              <form className="form-grid" onSubmit=${saveVoiceBackend}>
                <div className="form-row">
                  <label htmlFor="voice-backend">Backend</label>
                  <select
                    id="voice-backend"
                    value=${voiceBackendForm.voice_backend}
                    onChange=${(event) => setVoiceBackendForm({ voice_backend: event.target.value })}
                  >
                    ${getValue(state, ["status", "available_voice_backends"], ["twilio_native", "grok_voice_agent"]).map((backend) => html`
                      <option key=${backend} value=${backend}>${titleCase(backend)}</option>
                    `)}
                  </select>
                </div>

                <div className="key-grid">
                  <div className="key-tile">
                    <div className="key-label">Configured</div>
                    <div className="key-value">${getValue(state, ["status", "voice_backend_configured"], false) ? "Yes" : "No"}</div>
                  </div>
                  <div className="key-tile">
                    <div className="key-label">Ready</div>
                    <div className="key-value">${getValue(state, ["status", "voice_backend_ready"], false) ? "Yes" : "No"}</div>
                  </div>
                </div>

                <div className="source-row"><strong>Current mode:</strong> ${titleCase(getValue(state, ["status", "selected_voice_backend"], "twilio_native"))}</div>
                <div className="source-row"><strong>Hint:</strong> ${getValue(state, ["status", "voice_backend_hint"], "No voice backend notes available.")}</div>
                ${getValue(state, ["status", "voice_backend_websocket_url"], "") && html`
                  <div className="source-row"><strong>Realtime URL:</strong> ${getValue(state, ["status", "voice_backend_websocket_url"], "")}</div>
                `}

                <div className="toolbar">
                  <button type="submit" className="button button-primary" disabled=${savingVoiceBackend}>
                    ${savingVoiceBackend ? "Saving..." : "Save Voice Backend"}
                  </button>
                </div>
              </form>
            </article>

            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Backend and Hosting</h2>
                  <div className="panel-subtitle">
                    Current runtime posture for the hosted admin, AI model, public webhook, and telephony control mode.
                  </div>
                </div>
                <span className=${`badge ${statusBadgeClass(getValue(state, ["health", "status"], "Unknown"))}`}>${getValue(state, ["health", "status"], "Unknown")}</span>
              </div>

              <div className="key-grid">
                <div className="key-tile">
                  <div className="key-label">Backend Health</div>
                  <div className="key-value">${getValue(state, ["health", "status"], "Unknown")}</div>
                </div>
                <div className="key-tile">
                  <div className="key-label">Version</div>
                  <div className="key-value">${getValue(state, ["health", "version"], "Unknown")}</div>
                </div>
                <div className="key-tile">
                  <div className="key-label">AI Mode</div>
                  <div className="key-value">${titleCase(getValue(state, ["status", "ai_mode"], "unknown"))}</div>
                </div>
                <div className="key-tile">
                  <div className="key-label">Telephony Mode</div>
                  <div className="key-value">${getValue(state, ["status", "twilio_mock_mode"], false) ? "Mock" : "Live"}</div>
                </div>
              </div>

              <div className="stack" style=${{ marginTop: "18px" }}>
                <div className="source-row"><strong>Admin URL:</strong> ${window.location.origin}/admin/index.html</div>
                <div className="source-row"><strong>Webhook base:</strong> ${getValue(state, ["status", "webhook_base_url"], "Unavailable")}</div>
                <div className="source-row"><strong>Inbound webhook:</strong> ${getValue(state, ["status", "twilio_inbound_webhook"], "Unavailable")}</div>
                <div className="source-row"><strong>Inbound setup:</strong> ${getValue(state, ["status", "inbound_setup_hint"], "Connect a public Twilio webhook to answer inbound calls.")}</div>
                <div className="source-row"><strong>Call controls:</strong> ${titleCase(getValue(state, ["status", "call_controls_mode"], "unknown"))}</div>
                <div className="source-row"><strong>Voice backend:</strong> ${titleCase(getValue(state, ["status", "selected_voice_backend"], "twilio_native"))}</div>
              </div>
            </article>

            <article className="panel">
              <div className="panel-header">
                <div>
                  <h2 className="panel-title">Operator Notes</h2>
                  <div className="panel-subtitle">
                    Suggested operating rules for teams using this hosted admin panel with customer accounts.
                  </div>
                </div>
                <span className="badge badge-coral">High signal</span>
              </div>

              <div className="stack">
                <div className="source-row">Use <strong>Training</strong> for business facts and scripts rather than editing model code.</div>
                <div className="source-row">Use <strong>Calls</strong> to inspect prompt context and exact response flow when a call sounds robotic or wrong.</div>
                <div className="source-row">Reload knowledge after customer updates so the live system picks up new policies immediately.</div>
                <div className="source-row">If people should call your existing number, set call forwarding from that number to your Twilio voice number so the AI can answer first.</div>
                <div className="source-row">If the voice sounds robotic, improve the voice stack separately from the text prompt. This admin panel helps you diagnose the text side clearly.</div>
              </div>
            </article>
          </section>
        `}
      </main>

      ${toast && html`<div className="toast">${toast}</div>`}
    </div>
  `;
}

ReactDOM.createRoot(document.getElementById("root")).render(html`<${App} />`);
