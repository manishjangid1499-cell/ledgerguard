import React, { ReactNode, useState, useRef, useEffect } from 'react';
import { Box, Link, Typography, keyframes } from '@mui/material';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import { BrandLogo } from '../../shared/components/BrandLogo';

interface AuthCardProps {
  title: string;
  description: string;
  children: ReactNode;
  alternateText: string;
  alternateLabel: string;
  alternateRoute: '/login' | '/register';
}

const slideInFromRight = keyframes`
  from {
    opacity: 0;
    transform: translateX(24px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
`;

const slideInFromLeft = keyframes`
  from {
    opacity: 0;
    transform: translateX(-24px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
`;

const slideOutToLeft = keyframes`
  from {
    opacity: 1;
    transform: translateX(0);
  }
  to {
    opacity: 0;
    transform: translateX(-24px);
  }
`;

const slideOutToRight = keyframes`
  from {
    opacity: 1;
    transform: translateX(0);
  }
  to {
    opacity: 0;
    transform: translateX(24px);
  }
`;

const fadeInRise = keyframes`
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
`;

export const AuthCard: React.FC<AuthCardProps> = ({
  title,
  description,
  children,
  alternateText,
  alternateLabel,
  alternateRoute,
}) => {
  const location = useLocation();
  const navigate = useNavigate();
  const [isExiting, setIsExiting] = useState(false);
  const transitionTimeout = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (transitionTimeout.current) {
        window.clearTimeout(transitionTimeout.current);
      }
    };
  }, []);

  const fromSwitch = (location.state as { fromAuthSwitch?: string } | null)?.fromAuthSwitch;

  // Determine enter animation
  let enterAnimation = `${fadeInRise} 220ms ease-out forwards`;
  if (fromSwitch === '/login' && location.pathname === '/register') {
    enterAnimation = `${slideInFromRight} 260ms cubic-bezier(0.16, 1, 0.3, 1) forwards`;
  } else if (fromSwitch === '/register' && location.pathname === '/login') {
    enterAnimation = `${slideInFromLeft} 260ms cubic-bezier(0.16, 1, 0.3, 1) forwards`;
  }

  // Determine exit animation
  const exitAnimation =
    location.pathname === '/login'
      ? `${slideOutToLeft} 160ms cubic-bezier(0.4, 0, 0.2, 1) forwards`
      : `${slideOutToRight} 160ms cubic-bezier(0.4, 0, 0.2, 1) forwards`;

  const handleSwitch = (e: React.MouseEvent<HTMLAnchorElement>) => {
    // Let browser handle special clicks (meta, ctrl, shift, middle click)
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) {
      return;
    }
    e.preventDefault();
    if (isExiting) return;

    const isReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (isReducedMotion) {
      navigate(alternateRoute);
      return;
    }

    setIsExiting(true);
    transitionTimeout.current = window.setTimeout(() => {
      navigate(alternateRoute, {
        state: { fromAuthSwitch: location.pathname },
      });
    }, 160);
  };

  return (
    <Box
      id="auth-card"
      sx={{
        width: '100%',
        maxWidth: 440,
        bgcolor: '#ffffff',
        border: '1px solid',
        borderColor: 'rgba(15, 41, 66, 0.08)',
        borderRadius: '16px',
        boxShadow:
          '0 1px 3px 0 rgba(15, 41, 66, 0.04), 0 10px 25px -5px rgba(15, 41, 66, 0.06), 0 20px 40px -15px rgba(15, 41, 66, 0.04)',
        p: { xs: 2.5, sm: 3 },
        position: 'relative',
        overflow: 'hidden',
        transition: 'height 0.25s ease',
      }}
    >
      {/* Centered Brand Emblem */}
      <Box sx={{ display: 'flex', justifyContent: 'center', mb: 1.75 }}>
        <RouterLink
          to="/"
          aria-label="LedgerGuard home"
          style={{ textDecoration: 'none', display: 'inline-flex' }}
        >
          <BrandLogo size="medium" contrast="dark" subtitle={false} />
        </RouterLink>
      </Box>

      {/* Animated Content Viewport */}
      <Box
        sx={{
          animation: isExiting ? exitAnimation : enterAnimation,
          willChange: 'transform, opacity',
        }}
      >
        <Box sx={{ mb: 2, textAlign: 'center' }}>
          <Typography
            variant="h4"
            component="h1"
            sx={{
              fontWeight: 700,
              mb: 0.5,
              color: 'primary.main',
              fontSize: { xs: '1.375rem', sm: '1.5rem' },
              letterSpacing: '-0.025em',
            }}
          >
            {title}
          </Typography>
          <Typography
            variant="body2"
            sx={{
              color: 'text.secondary',
              lineHeight: 1.45,
              fontSize: '0.875rem',
            }}
          >
            {description}
          </Typography>
        </Box>

        {children}

        <Typography
          variant="body2"
          sx={{
            mt: 2.5,
            textAlign: 'center',
            lineHeight: 1.5,
            color: 'text.secondary',
            fontSize: '0.875rem',
          }}
        >
          {alternateText}{' '}
          <Link
            component={RouterLink}
            to={alternateRoute}
            onClick={handleSwitch}
            underline="hover"
            sx={{
              fontWeight: 600,
              color: 'secondary.main',
              cursor: 'pointer',
              '&:hover': {
                color: 'secondary.dark',
              },
            }}
          >
            {alternateLabel}
          </Link>
        </Typography>
      </Box>
    </Box>
  );
};
