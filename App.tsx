import React, { useState, useMemo } from 'react';
import { Calendar } from './components/Calendar';
import { Header } from './components/Header';
import { Stats } from './components/Stats';
import { EventModal } from './components/EventModal';
import { useCalendar } from './hooks/useCalendar';
import type { CalendarEvent, Parent } from './types';
import { useAuth } from './contexts/AuthContext';
import { LoginPage } from './components/LoginPage';

// Mock Data
const PARENTS: Parent[] = [
  { id: 'mom', name: 'With Mom', color: 'bg-rose-200', textColor: 'text-rose-800' },
  { id: 'dad', name: 'With Dad', color: 'bg-sky-200', textColor: 'text-sky-800' },
];

const generateInitialEvents = (): CalendarEvent[] => {
  const events: CalendarEvent[] = [];
  const today = new Date();
  
  const getWeekNumber = (d: Date): number => {
    d = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
    d.setUTCDate(d.getUTCDate() + 4 - (d.getUTCDay()||7));
    const yearStart = new Date(Date.UTC(d.getUTCFullYear(),0,1));
    const weekNo = Math.ceil((((d.getTime() - yearStart.getTime()) / 86400000) + 1)/7);
    return weekNo;
  };
  
  // Generate for current, previous and next month for better UX when navigating
  for (let m = -2; m <= 2; m++) {
    const dateRef = new Date(today.getFullYear(), today.getMonth() + m, 1);
    const year = dateRef.getFullYear();
    const month = dateRef.getMonth();
    const daysInMonth = new Date(year, month + 1, 0).getDate();

    for (let i = 1; i <= daysInMonth; i++) {
        const date = new Date(year, month, i);
        const weekNumber = getWeekNumber(date);
        
        // A continuous week-on, week-off schedule
        const parentId = weekNumber % 2 === 0 ? 'mom' : 'dad';
        events.push({ date: date.toISOString().split('T')[0], parentId });
    }
  }
  return events;
};


export default function App() {
  const { isLoggedIn } = useAuth();
  const [currentDate, setCurrentDate] = useState(new Date());
  const [events, setEvents] = useState<CalendarEvent[]>(generateInitialEvents());
  const [selectedDate, setSelectedDate] = useState<Date | null>(null);

  // FIX: Destructure `days` and `getMonth` to pass to the Calendar component, centralizing calendar logic.
  const {
    days,
    getMonth,
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
  
  if (!isLoggedIn) {
    return <LoginPage />;
  }


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
            {/* FIX: Pass `days` and `getMonth` from the parent to avoid a faulty hook call in the child. */}
            <Calendar
              currentDate={currentDate}
              days={days}
              getMonth={getMonth}
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