// FIX: Import React to bring the React namespace into scope for types like React.Dispatch.
import React, { useState, useMemo } from 'react';

export const useCalendar = (
  initialDate: Date, 
  setDate?: React.Dispatch<React.SetStateAction<Date>>
) => {
  const [currentDate, setCurrentDate] = useState(initialDate);
  const dateToUse = setDate ? initialDate : currentDate;
  const dateSetter = setDate ? setDate : setCurrentDate;

  const days = useMemo(() => {
    const year = dateToUse.getFullYear();
    const month = dateToUse.getMonth();

    const firstDayOfMonth = new Date(year, month, 1);
    const lastDayOfMonth = new Date(year, month + 1, 0);

    const firstDayOfWeek = firstDayOfMonth.getDay(); // 0 (Sun) - 6 (Sat)
    const daysInMonth = lastDayOfMonth.getDate();

    const calendarDays: Date[] = [];

    // Add days from previous month
    const prevMonthLastDay = new Date(year, month, 0).getDate();
    for (let i = firstDayOfWeek; i > 0; i--) {
      calendarDays.push(new Date(year, month - 1, prevMonthLastDay - i + 1));
    }

    // Add days from current month
    for (let i = 1; i <= daysInMonth; i++) {
      calendarDays.push(new Date(year, month, i));
    }

    // Add days from next month to fill the grid
    const remainingDays = 42 - calendarDays.length; // 6 weeks * 7 days
    for (let i = 1; i <= remainingDays; i++) {
      calendarDays.push(new Date(year, month + 1, i));
    }
    
    return calendarDays;
  }, [dateToUse]);

  const goToNextMonth = () => {
    dateSetter(new Date(dateToUse.getFullYear(), dateToUse.getMonth() + 1, 1));
  };

  const goToPreviousMonth = () => {
    dateSetter(new Date(dateToUse.getFullYear(), dateToUse.getMonth() - 1, 1));
  };

  const goToToday = () => {
    dateSetter(new Date());
  }

  return {
    days,
    currentMonthName: dateToUse.toLocaleString('default', { month: 'long' }),
    currentYear: dateToUse.getFullYear(),
    getMonth: () => dateToUse.getMonth(),
    getYear: () => dateToUse.getFullYear(),
    goToNextMonth,
    goToPreviousMonth,
    goToToday,
  };
};
