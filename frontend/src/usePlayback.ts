import { ref, type Ref } from "vue";

export function usePlayback(
  cursor: Ref<number>,
  maxSequence: Readonly<Ref<number>>,
  seek: (sequence: number, stop?: boolean) => Promise<void>,
  isDisposed: () => boolean,
) {
  const playing = ref(false);
  const speed = ref(1);
  let timer: ReturnType<typeof setTimeout> | undefined;

  function stopPlayback() {
    playing.value = false;
    if (timer) clearTimeout(timer);
  }

  async function play() {
    if (playing.value) {
      stopPlayback();
      return;
    }
    if (cursor.value >= maxSequence.value) await seek(0);
    playing.value = true;
    const advance = async () => {
      if (!playing.value || isDisposed()) return;
      if (cursor.value >= maxSequence.value) {
        stopPlayback();
        return;
      }
      await seek(cursor.value + 1, false);
      if (playing.value) timer = setTimeout(advance, 500 / speed.value);
    };
    timer = setTimeout(advance, 500 / speed.value);
  }

  return { playing, speed, stopPlayback, play };
}
