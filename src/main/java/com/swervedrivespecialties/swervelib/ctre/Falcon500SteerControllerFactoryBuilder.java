package com.swervedrivespecialties.swervelib.ctre;

import com.swervedrivespecialties.swervelib.*;
import edu.wpi.first.wpilibj.shuffleboard.ShuffleboardContainer;

import static com.swervedrivespecialties.swervelib.ctre.CtreUtils.checkCtreError;

import com.ctre.phoenixpro.configs.CurrentLimitsConfigs;
import com.ctre.phoenixpro.configs.FeedbackConfigs;
import com.ctre.phoenixpro.configs.MotionMagicConfigs;
import com.ctre.phoenixpro.configs.MotorOutputConfigs;
import com.ctre.phoenixpro.configs.Slot0Configs;
import com.ctre.phoenixpro.configs.VoltageConfigs;
import com.ctre.phoenixpro.controls.ControlRequest;
import com.ctre.phoenixpro.controls.MotionMagicDutyCycle;
import com.ctre.phoenixpro.controls.PositionDutyCycle;
import com.ctre.phoenixpro.hardware.TalonFX;
import com.ctre.phoenixpro.signals.FeedbackSensorSourceValue;
import com.ctre.phoenixpro.signals.InvertedValue;
import com.ctre.phoenixpro.signals.NeutralModeValue;

public final class Falcon500SteerControllerFactoryBuilder {
    private static final int CAN_TIMEOUT_MS = 250;
    private static final int STATUS_FRAME_GENERAL_PERIOD_MS = 250;

    private static final double TICKS_PER_ROTATION = 2048.0;

    // PID configuration
    private double proportionalConstant = Double.NaN;
    private double integralConstant = Double.NaN;
    private double derivativeConstant = Double.NaN;

    // Motion magic configuration
    private double velocityConstant = Double.NaN;
    private double accelerationConstant = Double.NaN;
    private double staticConstant = Double.NaN;

    private double nominalVoltage = Double.NaN;
    private double currentLimit = Double.NaN;

    public Falcon500SteerControllerFactoryBuilder withPidConstants(double proportional, double integral, double derivative) {
        this.proportionalConstant = proportional;
        this.integralConstant = integral;
        this.derivativeConstant = derivative;
        return this;
    }

    public boolean hasPidConstants() {
        return Double.isFinite(proportionalConstant) && Double.isFinite(integralConstant) && Double.isFinite(derivativeConstant);
    }

    public Falcon500SteerControllerFactoryBuilder withMotionMagic(double velocityConstant, double accelerationConstant, double staticConstant) {
        this.velocityConstant = velocityConstant;
        this.accelerationConstant = accelerationConstant;
        this.staticConstant = staticConstant;
        return this;
    }

    public boolean hasMotionMagic() {
        return Double.isFinite(velocityConstant) && Double.isFinite(accelerationConstant) && Double.isFinite(staticConstant);
    }

    public Falcon500SteerControllerFactoryBuilder withVoltageCompensation(double nominalVoltage) {
        this.nominalVoltage = nominalVoltage;
        return this;
    }

    public boolean hasVoltageCompensation() {
        return Double.isFinite(nominalVoltage);
    }

    public Falcon500SteerControllerFactoryBuilder withCurrentLimit(double currentLimit) {
        this.currentLimit = currentLimit;
        return this;
    }

    public boolean hasCurrentLimit() {
        return Double.isFinite(currentLimit);
    }

    public <T> SteerControllerFactory<ControllerImplementation, Falcon500SteerConfiguration<T>> build(AbsoluteEncoderFactory<T> absoluteEncoderFactory) {
        return new FactoryImplementation<>(absoluteEncoderFactory);
    }

    private class FactoryImplementation<T> implements SteerControllerFactory<ControllerImplementation, Falcon500SteerConfiguration<T>> {
        private final AbsoluteEncoderFactory<T> encoderFactory;

        private FactoryImplementation(AbsoluteEncoderFactory<T> encoderFactory) {
            this.encoderFactory = encoderFactory;
        }

        @Override
        public void addDashboardEntries(ShuffleboardContainer container, ControllerImplementation controller) {
            SteerControllerFactory.super.addDashboardEntries(container, controller);
            container.addNumber("Absolute Encoder Angle", () -> Math.toDegrees(controller.absoluteEncoder.getAbsoluteAngle()));
        }

        @Override
        public ControllerImplementation create(Falcon500SteerConfiguration<T> steerConfiguration, ModuleConfiguration moduleConfiguration) {
            AbsoluteEncoder absoluteEncoder = encoderFactory.create(steerConfiguration.getEncoderConfiguration());

            final double sensorPositionCoefficient = 2.0 * Math.PI / TICKS_PER_ROTATION * moduleConfiguration.getSteerReduction();
            final double sensorVelocityCoefficient = sensorPositionCoefficient * 10.0;

            var currentConfig = new CurrentLimitsConfigs();
            var slot0Config = new Slot0Configs();
            var motionMagicConfig = new MotionMagicConfigs();
            var voltageConfig = new VoltageConfigs();
            var motorOutputConfig = new MotorOutputConfigs();
            var feedbackConfig = new FeedbackConfigs();


            motorOutputConfig.Inverted = moduleConfiguration.isSteerInverted() ? InvertedValue.CounterClockwise_Positive : InvertedValue.Clockwise_Positive;
            motorOutputConfig.NeutralMode = NeutralModeValue.Brake;

            feedbackConfig.FeedbackSensorSource = FeedbackSensorSourceValue.RotorSensor;
            feedbackConfig.FeedbackRotorOffset = absoluteEncoder.getAbsoluteAngle() / sensorPositionCoefficient;

            if (hasPidConstants()) {
                slot0Config.kP = proportionalConstant;
                slot0Config.kI = integralConstant;
                slot0Config.kD = derivativeConstant;
            }
            if (hasMotionMagic()) {
                if (hasVoltageCompensation()) {
                    //TODO: Not sure if this is the correct replacement, was kF not kV
                    slot0Config.kV = (1023.0 * sensorVelocityCoefficient / nominalVoltage) * velocityConstant;
                }
                // TODO: What should be done if no nominal voltage is configured? Use a default voltage?

                // TODO: Make motion magic max voltages configurable or dynamically determine optimal values
                motionMagicConfig.MotionMagicCruiseVelocity = 2.0 / velocityConstant / sensorVelocityCoefficient;
                motionMagicConfig.MotionMagicAcceleration = (8.0 - 2.0) / accelerationConstant / sensorVelocityCoefficient;
            }
            if (hasVoltageCompensation()) {
                voltageConfig.PeakForwardVoltage = nominalVoltage;
                voltageConfig.PeakReverseVoltage = nominalVoltage;
            }
            if (hasCurrentLimit()) {
                currentConfig.SupplyCurrentLimit = currentLimit;
                currentConfig.StatorCurrentLimitEnable = true;
            }

            TalonFX motor = new TalonFX(steerConfiguration.getMotorPort());
            var motorConfigurator = motor.getConfigurator();
            motorConfigurator.apply(currentConfig);
            motorConfigurator.apply(slot0Config);
            motorConfigurator.apply(motionMagicConfig);
            motorConfigurator.apply(voltageConfig);
            motorConfigurator.apply(motorOutputConfig);

            if (hasVoltageCompensation()) {
                //motor.enableVoltageCompensation(true);
            }
            //checkCtreError(motor.configSelectedFeedbackSensor(TalonFXFeedbackDevice.IntegratedSensor, 0, CAN_TIMEOUT_MS), "Failed to set Falcon 500 feedback sensor");
            //motor.setSensorPhase(true);

            // Reduce CAN status frame rates
            // CtreUtils.checkCtreError(
            //         motor.setStatusFramePeriod(
            //                 StatusFrameEnhanced.Status_1_General,
            //                 STATUS_FRAME_GENERAL_PERIOD_MS,
            //                 CAN_TIMEOUT_MS
            //         ),
            //         "Failed to configure Falcon status frame period"
            // );

            return new ControllerImplementation(motor,
                    sensorPositionCoefficient,
                    sensorVelocityCoefficient,
                    hasMotionMagic(),
                    absoluteEncoder);
        }
    }

    private static class ControllerImplementation implements SteerController {
        private static final int ENCODER_RESET_ITERATIONS = 500;
        private static final double ENCODER_RESET_MAX_ANGULAR_VELOCITY = Math.toRadians(0.5);

        private final TalonFX motor;
        private final double motorEncoderPositionCoefficient;
        private final double motorEncoderVelocityCoefficient;
        private final boolean isMotionMagic;
        private final AbsoluteEncoder absoluteEncoder;

        private double referenceAngleRadians = 0.0;

        private double resetIteration = 0;

        private ControllerImplementation(TalonFX motor,
                                         double motorEncoderPositionCoefficient,
                                         double motorEncoderVelocityCoefficient,
                                         boolean motionMagic,
                                         AbsoluteEncoder absoluteEncoder) {
            this.motor = motor;
            this.motorEncoderPositionCoefficient = motorEncoderPositionCoefficient;
            this.motorEncoderVelocityCoefficient = motorEncoderVelocityCoefficient;
            this.isMotionMagic = motionMagic;
            this.absoluteEncoder = absoluteEncoder;
        }

        @Override
        public double getReferenceAngle() {
            return referenceAngleRadians;
        }

        @Override
        public void setReferenceAngle(double referenceAngleRadians) {
            double currentAngleRadians = motor.getPosition().getValue() * motorEncoderPositionCoefficient;

            // Reset the NEO's encoder periodically when the module is not rotating.
            // Sometimes (~5% of the time) when we initialize, the absolute encoder isn't fully set up, and we don't
            // end up getting a good reading. If we reset periodically this won't matter anymore.
            var feedbackConfig = new FeedbackConfigs();

            if (motor.getVelocity().getValue() * motorEncoderVelocityCoefficient < ENCODER_RESET_MAX_ANGULAR_VELOCITY) {
                if (++resetIteration >= ENCODER_RESET_ITERATIONS) {
                    resetIteration = 0;
                    double absoluteAngle = absoluteEncoder.getAbsoluteAngle();
                    feedbackConfig.FeedbackRotorOffset = absoluteAngle / motorEncoderPositionCoefficient;
                    motor.getConfigurator().apply(feedbackConfig);
                    currentAngleRadians = absoluteAngle;
                }
            } else {
                resetIteration = 0;
            }

            double currentAngleRadiansMod = currentAngleRadians % (2.0 * Math.PI);
            if (currentAngleRadiansMod < 0.0) {
                currentAngleRadiansMod += 2.0 * Math.PI;
            }

            // The reference angle has the range [0, 2pi) but the Falcon's encoder can go above that
            double adjustedReferenceAngleRadians = referenceAngleRadians + currentAngleRadians - currentAngleRadiansMod;
            if (referenceAngleRadians - currentAngleRadiansMod > Math.PI) {
                adjustedReferenceAngleRadians -= 2.0 * Math.PI;
            } else if (referenceAngleRadians - currentAngleRadiansMod < -Math.PI) {
                adjustedReferenceAngleRadians += 2.0 * Math.PI;
            }

            ControlRequest controlRequest;
            if(isMotionMagic) {
                controlRequest = new MotionMagicDutyCycle(adjustedReferenceAngleRadians / motorEncoderPositionCoefficient);
            } else {
                controlRequest = new PositionDutyCycle(adjustedReferenceAngleRadians / motorEncoderPositionCoefficient);
            }

            motor.setControl(controlRequest);


            this.referenceAngleRadians = referenceAngleRadians;
        }

        @Override
        public double getStateAngle() {
            double motorAngleRadians = motor.getPosition().getValue() * motorEncoderPositionCoefficient;
            motorAngleRadians %= 2.0 * Math.PI;
            if (motorAngleRadians < 0.0) {
                motorAngleRadians += 2.0 * Math.PI;
            }

            return motorAngleRadians;
        }

        @Override
        public void setCanStatusFramePeriodReductions() {
            System.out.println("Start Falcon Stear Can Reduction.");
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_1_General, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_2_Feedback0, 10);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_4_AinTempVbat, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_6_Misc, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_7_CommStatus, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_8_PulseWidth, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_9_MotProfBuffer, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_10_MotionMagic, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_10_Targets, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_11_UartGadgeteer, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_12_Feedback1, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_13_Base_PIDF0, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_14_Turn_PIDF1, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_15_FirmwareApiStatus, 255);
            // motor.setStatusFramePeriod(StatusFrameEnhanced.Status_Brushless_Current, 255);
            System.out.printf("Steer Falcon %1d: Reduced CAN message rates.", motor.getDeviceID());
            System.out.println();
        }

    }
}
