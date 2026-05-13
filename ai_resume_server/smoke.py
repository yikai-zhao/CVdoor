import os
from openai import OpenAI

key = os.getenv("OPENAI_API_KEY")
assert key, "OPENAI_API_KEY 没设置"

client = OpenAI(api_key=key)
resp = client.responses.create(
    model="gpt-4o-mini",   # 如果你的账号能用 gpt-5，可换成 "gpt-5-mini" 或 "gpt-5-chat-latest"
    input="Say hello in one short line."
)
print("OK:", resp.output_text)
