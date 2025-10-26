import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';

interface User {
  name: string;
  email: string;
  picture: string;
}

interface AuthContextType {
  user: User | null;
  isLoggedIn: boolean;
  login: () => void;
  logout: () => void;
  error: string | null;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    try {
      const storedUser = localStorage.getItem('coParentlyUser');
      if (storedUser) {
        setUser(JSON.parse(storedUser));
      }
      setError(null);
    } catch (error) {
      const errorMessage = "Failed to load user data from storage";
      console.error(errorMessage, error);
      setError(errorMessage);
      localStorage.removeItem('coParentlyUser');
    }
    setLoading(false);
  }, []);

  const login = () => {
    try {
      // Simulate Google OAuth login by creating a mock user
      const mockUser: User = {
        name: 'Alex Doe',
        email: 'alex.doe@example.com',
        picture: `https://i.pravatar.cc/150?u=alexdoe`,
      };
      localStorage.setItem('coParentlyUser', JSON.stringify(mockUser));
      setUser(mockUser);
      setError(null);
    } catch (error) {
      const errorMessage = "Failed to log in. Please try again.";
      console.error(errorMessage, error);
      setError(errorMessage);
    }
  };

  const logout = () => {
    try {
      localStorage.removeItem('coParentlyUser');
      setUser(null);
      setError(null);
    } catch (error) {
      const errorMessage = "Failed to log out. Please try again.";
      console.error(errorMessage, error);
      setError(errorMessage);
    }
  };

  const value = {
    user,
    isLoggedIn: !!user,
    login,
    logout,
    error,
  };

  // Avoid rendering children until the initial auth state is determined
  return (
    <AuthContext.Provider value={value}>
      {!loading && children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
