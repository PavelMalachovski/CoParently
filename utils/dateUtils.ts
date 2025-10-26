import {
  format,
  parseISO,
  getWeek,
  addMonths,
  startOfMonth,
  endOfMonth,
  getDaysInMonth,
  isSameMonth,
  isSameDay as dateFnsIsSameDay
} from 'date-fns';

/**
 * Formats a date to YYYY-MM-DD string format
 */
export const formatDateString = (date: Date): string => {
  return format(date, 'yyyy-MM-dd');
};

/**
 * Parses a YYYY-MM-DD string to a Date object
 */
export const parseDateString = (dateString: string): Date => {
  return parseISO(dateString);
};

/**
 * Gets the week number for a given date
 * Uses ISO week date system (week starts on Monday)
 */
export const getWeekNumber = (date: Date): number => {
  return getWeek(date, { weekStartsOn: 1 });
};

/**
 * Checks if two dates are the same day
 */
export const isSameDay = (date1: Date, date2: Date): boolean => {
  return dateFnsIsSameDay(date1, date2);
};

/**
 * Checks if a date is in the same month as another date
 */
export const isInSameMonth = (date: Date, referenceDate: Date): boolean => {
  return isSameMonth(date, referenceDate);
};

/**
 * Gets the number of days in a month for a given date
 */
export const getMonthDaysCount = (date: Date): number => {
  return getDaysInMonth(date);
};

/**
 * Formats a date for display (e.g., "January 15, 2024")
 */
export const formatDateForDisplay = (date: Date): string => {
  return format(date, 'MMMM d, yyyy');
};

/**
 * Formats a date with day of week (e.g., "Monday, January 15, 2024")
 */
export const formatDateWithWeekday = (date: Date): string => {
  return format(date, 'EEEE, MMMM d, yyyy');
};

/**
 * Gets the first day of the month for a given date
 */
export const getMonthStart = (date: Date): Date => {
  return startOfMonth(date);
};

/**
 * Gets the last day of the month for a given date
 */
export const getMonthEnd = (date: Date): Date => {
  return endOfMonth(date);
};

/**
 * Adds months to a date
 */
export const addMonthsToDate = (date: Date, months: number): Date => {
  return addMonths(date, months);
};

