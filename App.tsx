
import React, { useState, useMemo } from 'react';
import { Calendar } from './components/Calendar';
import { Header } from './components/Header';
import { Stats } from './components/Stats';
import { EventModal } from './components/EventModal';
import { useCalendar } from './hooks/useCalendar';
import type { CalendarEvent, Parent } from './types';

// Mock Data
const PARENTS: Parent[] = [
  { id: 'mom', name: 'With Mom', color: 'bg-rose-200', textColor: 'text-rose-800' },
  { id: 'dad', name: 'With Dad', color: 'bg-sky-200', textColor: 'text-sky-800' },
];

const generateInitialEvents = (): CalendarEvent[] => {
  const events: CalendarEvent[] = [];
  const today = new Date();
  const year = today.getFullYear();
  const month = today.getMonth();
  const daysInMonth = new Date(year, month + 1, 0).getDate();

  for (let i = 1; i <= daysInMonth; i++) {
    const date = new Date(year, month, i);
    // Simple alternating weekly schedule
    if (Math.floor((date.getDate() - 1) / 7) % 2 === 0) {
      if (date.getDay() >= 0 && date.getDay() <= 3) { // Sun-Weds with Mom
        events.push({ date: date.toISOString().split('T')[0], parentId: 'mom' });
      } else { // Thurs-Sat with Dad
        events.push({ date: date.toISOString().split('T')[0], parentId: 'dad' });
      }
    } else {
       if (date.getDay() >= 0 && date.getDay() <= 3) { // Sun-Weds with Dad
        events.push({ date: date.toISOString().split('T')[0], parentId: 'dad' });
      } else { // Thurs-Sat with Mom
        events.push({ date: date.toISOString().split('T')[0], parentId: 'mom' });
      }
    }
  }
  return events;
};


export default function App() {
  const [currentDate, setCurrentDate] = useState(new Date());
  const [events, setEvents] = useState<CalendarEvent[]>(generateInitialEvents());
  const [selectedDate, setSelectedDate] = useState<Date | null>(null);

  const {
    currentMonthName,
    currentYear,
    goToNextMonth,
    goToPreviousMonth,
    goToToday,
  } = useCalendar(currentDate, setCurrentDate);

  const handleDayClick = (date: Date) => {
    setSelectedDate(date);
  };

  const closeModal = () => {
    setSelectedDate(null);
  };

  const handleSaveEvent = (date: Date, parentId: string) => {
    const dateString = date.toISOString().split('T')[0];
    setEvents(prevEvents => {
      const existingEventIndex = prevEvents.findIndex(e => e.date === dateString);
      if (existingEventIndex > -1) {
        const updatedEvents = [...prevEvents];
        updatedEvents[existingEventIndex] = { ...updatedEvents[existingEventIndex], parentId };
        return updatedEvents;
      } else {
        return [...prevEvents, { date: dateString, parentId }];
      }
    });
    closeModal();
  };
  
  const handleDeleteEvent = (date: Date) => {
    const dateString = date.toISOString().split('T')[0];
    setEvents(prevEvents => prevEvents.filter(e => e.date !== dateString));
    closeModal();
  };

  const selectedEvent = useMemo(() => {
    if (!selectedDate) return undefined;
    const dateString = selectedDate.toISOString().split('T')[0];
    return events.find(e => e.date === dateString);
  }, [selectedDate, events]);


  return (
    <div className="min-h-screen bg-gray-50 text-gray-800 font-sans p-4 sm:p-6 lg:p-8">
      <div className="max-w-7xl mx-auto">
        <Header
          month={currentMonthName}
          year={currentYear}
          onPrev={goToPreviousMonth}
          onNext={goToNextMonth}
          onToday={goToToday}
        />
        <main className="mt-6 grid grid-cols-1 lg:grid-cols-3 gap-8">
          <div className="lg:col-span-2 bg-white p-4 sm:p-6 rounded-2xl shadow-sm">
            <Calendar
              currentDate={currentDate}
              events={events}
              parents={PARENTS}
              onDayClick={handleDayClick}
            />
          </div>
          <div className="lg:col-span-1">
             <Stats 
                events={events}
                parents={PARENTS}
                currentDate={currentDate}
             />
          </div>
        </main>
      </div>
      {selectedDate && (
        <EventModal
          isOpen={!!selectedDate}
          onClose={closeModal}
          date={selectedDate}
          event={selectedEvent}
          parents={PARENTS}
          onSave={handleSaveEvent}
          onDelete={handleDeleteEvent}
        />
      )}
    </div>
  );
}
