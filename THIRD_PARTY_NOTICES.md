# Componentes de voz incluidos

VladER incorpora modelos Piper mediante sherpa-onnx. Se conservan los avisos de
los autores y los textos de licencia en `app/src/main/assets/licenses/`.

| Componente | Licencia / atribución | Fuente |
| --- | --- | --- |
| sherpa-onnx 1.13.8 | Apache-2.0, Xiaomi Corporation y colaboradores | https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8 |
| ONNX Runtime | MIT, Microsoft Corporation | https://github.com/microsoft/onnxruntime |
| piper-phonemize | MIT, Michael Hansen | https://github.com/csukuangfj/piper-phonemize/tree/f3ff95afc03640bc1399e113e83361192a2fafb4 |
| eSpeak NG | GPL-3.0, autores de eSpeak/eSpeak NG | https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1 |
| Claude es_MX high | Tarjeta: datos Apache-2.0; HirCoir/Piper-TTS-Spanish | https://huggingface.co/rhasspy/piper-voices/blob/main/es/es_MX/claude/high/MODEL_CARD |
| DaveFX es_ES medium | Tarjeta: datos CC0; voice-datasets; ajuste de lessac | https://huggingface.co/rhasspy/piper-voices/blob/main/es/es_ES/davefx/medium/MODEL_CARD |

El repositorio original de voces declara MIT; se mantienen además las condiciones
específicas de cada tarjeta. Los paquetes convertidos int8 se obtienen de los
releases oficiales de k2-fsa/sherpa-onnx y se verifican por SHA-256. No se utiliza
ningún servicio de clonación de voz ni se envía el contenido del libro a servidores.

Las fuentes nativas correspondientes y las instrucciones de compilación están
identificadas en [docs/PIPER.md](docs/PIPER.md). Los textos de licencias son de sus
respectivos titulares; no constituyen una afirmación de autoría sobre esas voces.
