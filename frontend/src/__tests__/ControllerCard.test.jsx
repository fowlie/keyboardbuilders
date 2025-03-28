import React from 'react';
import { render, screen } from '@testing-library/react';
import ControllerCard from '../components/ControllerCard';
import '@testing-library/jest-dom';

describe('ControllerCard Component', () => {
  const mockController = {
    id: 1,
    name: 'Pro Micro',
    manufacturer: 'SparkFun',
    chipset: 'ATmega32U4',
    url: 'https://example.com/promicro'
  };

  it('renders correctly with all controller data', () => {
    render(<ControllerCard controller={mockController} />);
    
    expect(screen.getByText('Pro Micro')).toBeInTheDocument();
    expect(screen.getByText('SparkFun')).toBeInTheDocument();
    expect(screen.getByText(/Chipset: ATmega32U4/i)).toBeInTheDocument();
    expect(screen.getByText('More Info')).toBeInTheDocument();
    
    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('href', 'https://example.com/promicro');
  });

  it('renders correctly with partial controller data', () => {
    const partialController = {
      id: 2,
      name: 'STM32F4',
      chipset: 'STM32F401',
    };
    
    render(<ControllerCard controller={partialController} />);
    
    expect(screen.getByText('STM32F4')).toBeInTheDocument();
    expect(screen.getByText(/Chipset: STM32F401/i)).toBeInTheDocument();
    expect(screen.queryByText('More Info')).not.toBeInTheDocument();
  });

  it('renders nothing when controller is null', () => {
    const { container } = render(<ControllerCard controller={null} />);
    expect(container).toBeEmptyDOMElement();
  });
}); 