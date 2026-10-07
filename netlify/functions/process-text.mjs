const headers = {
  "Content-Type": "application/json; charset=utf-8",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Content-Type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(status, payload) {
  return new Response(JSON.stringify(payload), { status, headers });
}

function getOutputText(data) {
  if (typeof data?.output_text === "string" && data.output_text.trim()) {
    return data.output_text.trim();
  }

  const texts = [];
  for (const item of data?.output ?? []) {
    for (const content of item?.content ?? []) {
      if (typeof content?.text === "string") texts.push(content.text);
    }
  }
  return texts.join("\n").trim();
}

const schema = {
  type: "object",
  additionalProperties: false,
  properties: {
    cleanedText: { type: "string" },
    translation: { type: "string" },
    summary: { type: "string" },
    notes: { type: "string" },
    confidence: { type: "string", enum: ["high", "medium", "low"] },
    warnings: {
      type: "array",
      items: { type: "string" },
    },
  },
  required: ["cleanedText", "translation", "summary", "notes", "confidence", "warnings"],
};

function buildInstructions(mode, sourceLanguage, targetLanguage) {
  const source = sourceLanguage || "the source language";
  const target = targetLanguage || "the requested target language";

  return `You are the language intelligence layer of a long-form AI dictaphone.

Input language: ${source}
Target language: ${target}
Mode: ${mode}

Your job is to turn noisy speech-to-text output into useful text without inventing facts.

Rules:
1. Preserve the speaker's meaning, names, numbers, dates, places, commitments, and technical terms.
2. Correct obvious transcription artifacts, punctuation, capitalization, spacing, and sentence boundaries.
3. Do not silently invent missing content. If the transcript is ambiguous, preserve the ambiguity and put a concise warning in the warnings array.
4. cleanedText must remain in the source language.
5. In translate mode, provide a faithful natural translation in the target language.
6. In notes mode, produce concise structured notes and a short summary in the target language when a target language is provided; otherwise use the source language.
7. In dictate mode, leave translation, summary, and notes as empty strings.
8. If the transcript contains an obvious accidental adjacent repetition caused by speech recognition (for example, the same phrase copied twice with no meaningful change), remove only the accidental duplicate. Preserve intentional repetition used for emphasis or meaning.
9. Do not mention that you are an AI unless the user content itself requires it.
10. Do not add opinions or conclusions not supported by the input.
11. Prefer natural written language over word-for-word speech fillers, but do not erase meaningful qualifiers or uncertainty.`;
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
    const body = await request.json();
    const text = typeof body?.text === "string" ? body.text.trim() : "";
    const mode = typeof body?.mode === "string" ? body.mode : "dictate";
    const sourceLanguage = typeof body?.sourceLanguage === "string" ? body.sourceLanguage : "";
    const targetLanguage = typeof body?.targetLanguage === "string" ? body.targetLanguage : "";

    if (!text) return json(400, { error: "text is required." });

    const safeMode = ["dictate", "translate", "notes"].includes(mode) ? mode : "dictate";
    const userPrompt = `Process the following speech transcript.\n\n${text}`;

    const upstream = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: "gpt-5-mini",
        store: false,
        input: [
          {
            role: "system",
            content: [{ type: "input_text", text: buildInstructions(safeMode, sourceLanguage, targetLanguage) }],
          },
          {
            role: "user",
            content: [{ type: "input_text", text: userPrompt }],
          },
        ],
        text: {
          format: {
            type: "json_schema",
            name: "dictaphone_result",
            strict: true,
            schema,
          },
        },
      }),
    });

    const data = await upstream.json();
    if (!upstream.ok) {
      return json(upstream.status, {
        error: data?.error?.message || "OpenAI AI-processing request failed.",
      });
    }

    const outputText = getOutputText(data);
    if (!outputText) {
      return json(502, { error: "AI processing returned no text." });
    }

    let result;
    try {
      result = JSON.parse(outputText);
    } catch {
      return json(502, { error: "AI processing returned invalid structured output." });
    }

    return json(200, result);
  } catch (error) {
    console.error("process-text failed", error);
    return json(500, { error: "AI processing failed." });
  }
};
