
import React, { useState, useRef, useEffect } from 'react';
import { ChevronLeftIcon, ChevronRightIcon } from './IconComponents';
import { useAuth } from '../contexts/AuthContext';

interface HeaderProps {
  month: string;
  year: number;
  onPrev: () => void;
  onNext: () => void;
  onToday: () => void;
}

export const Header: React.FC<HeaderProps> = ({ month, year, onPrev, onNext, onToday }) => {
  const { user, logout } = useAuth();
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setDropdownOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [dropdownRef]);

  return (
    <header className="flex flex-col sm:flex-row items-center justify-between space-y-4 sm:space-y-0">
      <div className="flex items-center">
        <img src="https://i.ibb.co/6wmTN1v/Co-Parently.png" alt="CoParently Logo" className="h-10" />
      </div>
      <div className="flex items-center space-x-4">
        <div className="flex items-center space-x-2 sm:space-x-4 bg-white p-2 rounded-xl shadow-sm border border-gray-100">
          <button onClick={onPrev} className="p-2 rounded-md hover:bg-gray-100 text-gray-500 hover:text-gray-700 transition" aria-label="Previous month">
              <ChevronLeftIcon />
          </button>
          <h2 className="text-lg font-semibold text-gray-700 w-32 text-center" aria-live="polite">{month} {year}</h2>
          <button onClick={onNext} className="p-2 rounded-md hover:bg-gray-100 text-gray-500 hover:text-gray-700 transition" aria-label="Next month">
              <ChevronRightIcon />
          </button>
          <button onClick={onToday} className="hidden sm:block px-4 py-2 text-sm font-semibold text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-md transition">
              Today
          </button>
        </div>
        
        {user && (
          <div className="relative" ref={dropdownRef}>
            <button onClick={() => setDropdownOpen(prev => !prev)} className="rounded-full focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500" id="user-menu-button" aria-expanded={dropdownOpen} aria-haspopup="true">
              <span className="sr-only">Open user menu</span>
              <img className="w-10 h-10 rounded-full" src={user.picture} alt={`Logged in as ${user.name}`} />
            </button>
            {dropdownOpen && (
              <div className="origin-top-right absolute right-0 mt-2 w-56 rounded-md shadow-lg bg-white ring-1 ring-black ring-opacity-5 focus:outline-none z-10" role="menu" aria-orientation="vertical" aria-labelledby="user-menu-button">
                <div className="py-1" role="none">
                  <div className="px-4 py-3">
                    <p className="text-sm font-semibold text-gray-900" role="none">{user.name}</p>
                    <p className="text-sm text-gray-500 truncate" role="none">{user.email}</p>
                  </div>
                  <div className="border-t border-gray-100"></div>
                  <button onClick={logout} className="block w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100" role="menuitem">
                    Sign out
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </header>
  );
};