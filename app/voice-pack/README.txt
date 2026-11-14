幽默输入法 语音包 / Youmo IME voice pack

What is in it, where it comes from, and under which licence. The code that reads it is the app's
(https://github.com/Sraw/youmo-ime, LGPL-2.1-or-later); a pack carries no code.

encoder.ort, decoder.ort, joiner.ort, tokens.txt, bpe.vocab: the speech recognizer
  X-ASR zh-en (https://github.com/Gilgamesh-J/X-ASR), Apache-2.0, as sherpa-onnx exports it (int8);
  its encoder fine-tuned by this project for accented Mandarin on speech synthesized by CosyVoice3
  (Apache-2.0) and Common Voice (CC0), held to the original by AISHELL-1 (Apache-2.0) and LibriSpeech
  (https://www.openslr.org/12/, CC BY 4.0); converted to onnxruntime's ORT format. Apache-2.0.
  The fine-tuned version was chosen among several on test sets that include KeSpeech (research
  only); KeSpeech was not trained on.

silero_vad.onnx: silero VAD (https://github.com/snakers4/silero-vad), MIT, unchanged.

hotwords.txt: this project's new-word list, spelled for the recognizer.

polish-tagger.ort, polish-tagger.json: the polish tagger (drops fillers and words taken back, adds
  punctuation, writes numbers in digits), trained by this project, O061f-s3-3000 (2026-11-13).
  Licensed under the Creative Commons Attribution-ShareAlike 4.0 International licence (CC BY-SA 4.0):
  LICENSE-CC-BY-SA-4.0.txt, https://creativecommons.org/licenses/by-sa/4.0/
  It is a derived work of:
  - hfl/chinese-electra-180g-small-ex-discriminator (https://github.com/ymcui/Chinese-ELECTRA),
    Apache-2.0: the model it was trained from;
  - AliMeeting by Alibaba Group (Yu et al., "M2MeT: The ICASSP 2022 Multi-Channel Multi-Party Meeting
    Transcription Challenge", ICASSP 2022; https://www.openslr.org/119/), CC BY-SA 4.0: its
    transcripts, and their polished versions written by Claude (Anthropic) for this project, are the
    real and seam training data;
  - FineWeb-2 (https://huggingface.co/datasets/HuggingFaceFW/fineweb-2, cmn_Hani), ODC-By 1.0, and
    Common Crawl's terms of use: sentences made into synthetic speech transcripts (fillers and
    take-backs inserted by rule) and number readings by this project's rules.
  No non-commercial, research-only or agreement-bound data was used to train it or to pick it.
  Changes: trained (fine-tuned) on the data above, quantized to int8 and converted to ORT format.
  How it was made: from ELECTRA, in three stages, each going on from the last: AliMeeting's 50,007
  polished stretches of one speaker (452 speakers) with synthetic transcripts (FineWeb-2 sentences
  with fillers and take-backs inserted at AliMeeting's rates); the number readings added, with a
  head that marks numbers; 4,902 recognizer outputs of long AliMeeting stretches added, polished
  across the seams where the recognizer cut. The step was picked on 500 stretches of AliMeeting's
  Eval speakers and on the recognizer's streamed output of seven public and project test sets
  (AISHELL-1, FLEURS, ASCEND, Common Voice, AliMeeting, new words).
  Measured on 1,000 stretches of AliMeeting's Test speakers, not trained on (the transcripts without
  their punctuation; polished text against Claude's): 9.15% of characters wrong with punctuation
  counted, against 12.49% for the previous tagger (0.6.0, voice pack v2); on the seven sets'
  streamed output, 17.23% against 18.70%.
