const headers = {
  "Content-Type": "application/json; charset=utf-8",
  "Access-Control-Allow-Origin": "*",
};

export default async () => {
  return new Response(
    JSON.stringify({
      ok: true,
      service: "ai-dictaphone-backend",
      openaiConfigured: Boolean(process.env.OPENAI_API_KEY),
    }),
    { status: 200, headers },
  );
};
