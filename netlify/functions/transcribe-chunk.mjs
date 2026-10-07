const headers = {
  "Content-Type": "application/json; charset=utf-8",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Content-Type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(status, payload) {
  return new Response(JSON.stringify(payload), { status, headers });
}

export default async (request) => {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers });
  }

  if (request.method !== "POST") {
    return json(405, { error: "POST is required." });
  }

  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    return json(500, { error: "OPENAI_API_KEY is not configured on the server." });
  }

  try {
    const form = await request.formData();
    const audio = form.get("audio");
    const language = String(form.get("language") ?? "").trim();

    if (!(audio instanceof File)) {
      return json(400, { error: "audio file is required." });
    }

    if (audio.size === 0) {
      return json(400, { error: "audio file is empty." });
    }

    const upstreamForm = new FormData();
    upstreamForm.append("file", audio, audio.name || "chunk.wav");
    upstreamForm.append("model", "gpt-4o-transcribe");
    upstreamForm.append("response_format", "json");

    if (language && language !== "auto") {
      upstreamForm.append("language", language);
    }

    const upstream = await fetch("https://api.openai.com/v1/audio/transcriptions", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
      },
      body: upstreamForm,
    });

    const data = await upstream.json();
    if (!upstream.ok) {
      return json(upstream.status, {
        error: data?.error?.message || "OpenAI transcription request failed.",
      });
    }

    return json(200, {
      text: typeof data?.text === "string" ? data.text.trim() : "",
    });
  } catch (error) {
    console.error("transcribe-chunk failed", error);
    return json(500, { error: "Transcription failed." });
  }
};
