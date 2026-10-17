# Qwen tokenizer resource

Copyright (c) Alibaba, Inc. and its affiliates. Apache-2.0; see LICENSE-DASHSCOPE.
Source: https://github.com/dashscope/dashscope-sdk-python/tree/2cd356a499e7d70dc28035b34fd9ee1ad2d12572
Unmodified resource: dashscope/resources/qwen.tiktoken
SHA-256: b2b1b8dfb5cc5f024bafc373121c6aba3f66f9a5a0269e243470a1de16a33186
Regex and NFC behavior follow dashscope/tokenizers/qwen_tokenizer.py at the same revision.
Java integration uses JTokkit 1.1.0 (MIT); byte-pair encoding vocabulary is Qwen, not an OpenAI model vocabulary.
Local counts exclude the provider's private chat template; request reservation adds framing allowance and validates each actual usage response.
