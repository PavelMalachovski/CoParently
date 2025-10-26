import React, { useState, useMemo, useEffect } from 'react';
import { Calendar } from './components/Calendar';
import { Header } from './components/Header';
import { Stats } from './components/Stats';
import { EventModal } from './components/EventModal';
import { useCalendar } from './hooks/useCalendar';
import { useAuth } from './contexts/AuthContext';
import { LoginPage } from './components/LoginPage';
import { useEventsStore } from './stores/useEventsStore';
import { useParentsStore } from './stores/useParentsStore';
import { formatDateString } from './utils/dateUtils';

export default function App() {
  const { isLoggedIn } = useAuth();
  const [currentDate, setCurrentDate] = useState(new Date());
  const [selectedDate, setSelectedDate] = useState<Date | null>(null);

  // Use Zustand stores
  const events = useEventsStore((state) => state.events);
  const updateEvent = useEventsStore((state) => state.updateEvent);
  const deleteEvent = useEventsStore((state) => state.deleteEvent);
  const initializeDefaultSchedule = useEventsStore((state) => state.initializeDefaultSchedule);

  const parents = useParentsStore((state) => state.parents);

  // Initialize default schedule on first load
  useEffect(() => {
    initializeDefaultSchedule();
  }, [initializeDefaultSchedule]);

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
    const dateString = formatDateString(date);
    updateEvent(dateString, parentId);
    closeModal();
  };

  const handleDeleteEvent = (date: Date) => {
    const dateString = formatDateString(date);
    deleteEvent(dateString);
    closeModal();
  };

  const selectedEvent = useMemo(() => {
    if (!selectedDate) return undefined;
    const dateString = formatDateString(selectedDate);
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
              parents={parents}
              onDayClick={handleDayClick}
            />
          </div>
          <div className="lg:col-span-1">
             <Stats
                events={events}
                parents={parents}
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
          parents={parents}
          onSave={handleSaveEvent}
          onDelete={handleDeleteEvent}
        />
      )}
    </div>
  );
}
