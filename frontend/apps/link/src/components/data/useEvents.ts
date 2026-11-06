import { useEffect, useState } from 'react';
import { getJson, sortEvents, type EventRow } from './dataApi';

/** Every event, newest first, for the pickers on the data screens. */
export function useEvents(): { events: EventRow[]; eventsError: string } {
  const [events, setEvents] = useState<EventRow[]>([]);
  const [eventsError, setEventsError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    getJson<EventRow[]>('/api/admin/sessions', controller.signal)
      .then(rows => setEvents(sortEvents(rows)))
      .catch(err => {
        if (!controller.signal.aborted) setEventsError(err instanceof Error ? err.message : '이벤트를 불러오지 못했어요.');
      });
    return () => controller.abort();
  }, []);
  return { events, eventsError };
}
