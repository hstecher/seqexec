// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package seqexec.server.tcs

import cats.effect.{ Async, IO, Ref }
import cats.syntax.all._
import edu.gemini.seqexec.server.tcs.{ BinaryOnOff, BinaryYesNo }
import lucuma.core.enums.LightSinkName.Gmos
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import seqexec.model.{ M1GuideConfig, M2GuideConfig, TelescopeGuideConfig }
import seqexec.model.`enum`.{ ComaOption, Instrument, M1Source, MountGuideOption, TipTiltSource }
import seqexec.server.InstrumentGuide
import seqexec.server.altair.AltairController
import seqexec.server.gems.Gems
import seqexec.server.gems.Gems._
import seqexec.server.gems.GemsController._
import seqexec.server.tcs.TcsController.LightSource.Sky
import seqexec.server.tcs.TcsController.{
  AGConfig,
  AoGuidersConfig,
  AoTcsConfig,
  GuiderConfig,
  GuiderSensorOff,
  GuiderSensorOn,
  InstrumentOffset,
  LightPath,
  NodChopTrackingConfig,
  OIConfig,
  OffsetP,
  OffsetQ,
  P1Config,
  ProbeTrackingConfig,
  Subsystem,
  TelescopeConfig
}
import seqexec.server.tcs.TcsSouthController._
import seqexec.server.tcs.TestTcsEpics.{ ProbeGuideConfigVals, TestTcsEvent }
import shapeless.tag
import squants.Time
import squants.space.Length
import squants.space.LengthConversions.LengthConversions
import squants.space.AngleConversions.AngleConversions

class TcsSouthControllerEpicsAoSpec extends CatsEffectSuite {
  import TcsSouthControllerEpicsAoSpec._

  private implicit def unsafeLogger: Logger[IO] = NoOpLogger.impl[IO]

  private val baseStateWithGeMSPlusP1Guiding = TestTcsEpics.defaultState.copy(
    absorbTipTilt = 1,
    m1GuideSource = "GAOS",
    m1Guide = BinaryOnOff.On,
    m2GuideState = BinaryOnOff.On,
    m2p1Guide = "ON",
    p1FollowS = "On",
    p1Parked = false,
    pwfs1On = BinaryYesNo.Yes,
    cwfs1Follow = true,
    cwfs2Follow = true,
    cwfs3Follow = true,
    sfName = "gsaoi",
    g1MapName = GemsSource.Cwfs1.some,
    g2MapName = GemsSource.Cwfs2.some,
    g3MapName = GemsSource.Cwfs3.some,
    pwfs1ProbeGuideConfig = ProbeGuideConfigVals(1, 0, 0, 1),
    g1GuideConfig = ProbeGuideConfigVals(1, 0, 0, 1),
    g2GuideConfig = ProbeGuideConfigVals(1, 0, 0, 1),
    g3GuideConfig = ProbeGuideConfigVals(1, 0, 0, 1),
    gsaoiPort = 1,
    comaCorrect = "On",
    useAo = BinaryYesNo.Yes
  )
  private val baseConfig: TcsSouthAoConfig   = AoTcsConfig[GemsGuiders, GemsConfig](
    TelescopeGuideConfig(MountGuideOption.MountGuideOff,
                         M1GuideConfig.M1GuideOff,
                         M2GuideConfig.M2GuideOff
    ),
    TelescopeConfig(None, None),
    AoGuidersConfig(
      tag[P1Config](
        GuiderConfig(ProbeTrackingConfig.On(NodChopTrackingConfig.Normal), GuiderSensorOn)
      ),
      GemsGuiders(
        tag[CWFS1Config](
          GuiderConfig(ProbeTrackingConfig.On(NodChopTrackingConfig.Normal), GuiderSensorOn)
        ),
        tag[CWFS2Config](
          GuiderConfig(ProbeTrackingConfig.On(NodChopTrackingConfig.Normal), GuiderSensorOn)
        ),
        tag[CWFS3Config](
          GuiderConfig(ProbeTrackingConfig.On(NodChopTrackingConfig.Normal), GuiderSensorOn)
        ),
        tag[ODGW1Config](GuiderConfig(ProbeTrackingConfig.Off, GuiderSensorOff)),
        tag[ODGW2Config](GuiderConfig(ProbeTrackingConfig.Off, GuiderSensorOff)),
        tag[ODGW3Config](GuiderConfig(ProbeTrackingConfig.Off, GuiderSensorOff)),
        tag[ODGW4Config](GuiderConfig(ProbeTrackingConfig.Off, GuiderSensorOff))
      ),
      tag[OIConfig](GuiderConfig(ProbeTrackingConfig.Off, GuiderSensorOff))
    ),
    AGConfig(LightPath(Sky, Gmos), None),
    GemsOn(
      Cwfs1Usage.Use,
      Cwfs2Usage.Use,
      Cwfs3Usage.Use,
      Odgw1Usage.DontUse,
      Odgw2Usage.DontUse,
      Odgw3Usage.DontUse,
      Odgw4Usage.DontUse,
      P1Usage.Use,
      OIUsage.DontUse
    ),
    DummyInstrument(Instrument.Gsaoi, 1.millimeters.some)
  )

  test("Don't touch guiding if configuration does not change") {

    val dumbEpics = buildTcsEpics[IO](baseStateWithGeMSPlusP1Guiding)

    val config: TcsSouthAoConfig = baseConfig.copy(
      gc = TelescopeGuideConfig(
        MountGuideOption.MountGuideOn,
        M1GuideConfig.M1GuideOn(M1Source.GAOS),
        M2GuideConfig.M2GuideOn(ComaOption.ComaOn, Set(TipTiltSource.GAOS))
      ),
      gds = baseConfig.gds.copy(
        pwfs1 = tag[P1Config](
          GuiderConfig(ProbeTrackingConfig.On(NodChopTrackingConfig.Normal), GuiderSensorOn)
        )
      )
    )

    val gemsConfig = GemsOn(
      Cwfs1Usage.Use,
      Cwfs2Usage.Use,
      Cwfs3Usage.Use,
      Odgw1Usage.DontUse,
      Odgw2Usage.DontUse,
      Odgw3Usage.DontUse,
      Odgw4Usage.DontUse,
      P1Usage.Use,
      OIUsage.DontUse
    )

    val gems = new Gems[IO] {
      override val cfg: GemsConfig = gemsConfig

      override def pauseResume(
        pauseReasons:  Gaos.PauseConditionSet,
        resumeReasons: Gaos.ResumeConditionSet
      ): IO[Gaos.PauseResume[IO]] =
        Gaos.PauseResume[IO](none, none).pure[IO]

      override val stateGetter: GemsWfsState[IO] = Gems.GemsWfsState[IO](
        Cwfs1DetectorState.On.pure[IO],
        Cwfs2DetectorState.On.pure[IO],
        Cwfs3DetectorState.On.pure[IO],
        Odgw1DetectorState.Off.pure[IO],
        Odgw2DetectorState.Off.pure[IO],
        Odgw3DetectorState.Off.pure[IO],
        Odgw4DetectorState.Off.pure[IO]
      )

      override def observe(
        config:  Either[AltairController.AltairConfig, GemsConfig],
        expTime: Time
      ): IO[Unit] = IO.unit

      override def endObserve(config: Either[AltairController.AltairConfig, GemsConfig]): IO[Unit] =
        IO.unit
    }

    for {
      d <- dumbEpics
      c  = TcsSouthControllerEpicsAo(d)
      _ <- c.applyAoConfig(TcsController.Subsystem.allButGaosNorOi.add(Subsystem.Gaos),
                           gems,
                           gemsConfig,
                           config
           )
      r <- d.outputF
    } yield assert(r.isEmpty)

  }

  private val guideOffEvents = List(
    TestTcsEvent.M1GuideCmd("off"),
    TestTcsEvent.M2GuideCmd("off"),
    TestTcsEvent.M2GuideConfigCmd("", "", "on"),
    TestTcsEvent.MountGuideCmd("", "off")
  )

  private val guideOnEvents = List(
    TestTcsEvent.M1GuideCmd("on"),
    TestTcsEvent.M2GuideCmd("on"),
    TestTcsEvent.MountGuideCmd("", "on")
  )

  test("Pause and resume guiding for offsets") {

    val dumbEpics = buildTcsEpics[IO](baseStateWithGeMSPlusP1Guiding)

    val config: TcsSouthAoConfig = baseConfig.copy(
      tc = baseConfig.tc.copy(offsetA =
        InstrumentOffset(tag[OffsetP](10.arcseconds), tag[OffsetQ](-5.arcseconds)).some
      ),
      gc = TelescopeGuideConfig(
        MountGuideOption.MountGuideOn,
        M1GuideConfig.M1GuideOn(M1Source.GAOS),
        M2GuideConfig.M2GuideOn(ComaOption.ComaOn, Set(TipTiltSource.GAOS))
      ),
      gds = baseConfig.gds.copy(
        pwfs1 = tag[P1Config](
          GuiderConfig(ProbeTrackingConfig.On(NodChopTrackingConfig.Normal), GuiderSensorOn)
        )
      )
    )

    val gemsConfig = GemsOn(
      Cwfs1Usage.Use,
      Cwfs2Usage.Use,
      Cwfs3Usage.Use,
      Odgw1Usage.DontUse,
      Odgw2Usage.DontUse,
      Odgw3Usage.DontUse,
      Odgw4Usage.DontUse,
      P1Usage.Use,
      OIUsage.DontUse
    )

    val gems = new Gems[IO] {
      override val cfg: GemsConfig = gemsConfig

      override def pauseResume(
        pauseReasons:  Gaos.PauseConditionSet,
        resumeReasons: Gaos.ResumeConditionSet
      ): IO[Gaos.PauseResume[IO]] =
        Gaos.PauseResume[IO](IO.unit.some, IO.unit.some).pure[IO]

      override val stateGetter: GemsWfsState[IO] = Gems.GemsWfsState[IO](
        Cwfs1DetectorState.On.pure[IO],
        Cwfs2DetectorState.On.pure[IO],
        Cwfs3DetectorState.On.pure[IO],
        Odgw1DetectorState.Off.pure[IO],
        Odgw2DetectorState.Off.pure[IO],
        Odgw3DetectorState.Off.pure[IO],
        Odgw4DetectorState.Off.pure[IO]
      )

      override def observe(
        config:  Either[AltairController.AltairConfig, GemsConfig],
        expTime: Time
      ): IO[Unit] = IO.unit

      override def endObserve(config: Either[AltairController.AltairConfig, GemsConfig]): IO[Unit] =
        IO.unit
    }

    for {
      d <- dumbEpics
      c  = TcsSouthControllerEpicsAo(d)
      _ <- c.applyAoConfig(TcsController.Subsystem.allButGaosNorOi.add(Subsystem.Gaos),
                           gems,
                           gemsConfig,
                           config
           )
      r <- d.outputF
    } yield {
      val (head, tail) = r.span {
        case TestTcsEvent.OffsetACmd(_, _) => false
        case _                             => true
      }

      assert(guideOffEvents.forall(head.contains))
      assert(guideOnEvents.forall(tail.contains))
    }

  }

  test("Pause guiding for a sky") {

    val dumbEpics = buildTcsEpics[IO](baseStateWithGeMSPlusP1Guiding)

    val config: TcsSouthAoConfig = baseConfig.copy(
      tc = baseConfig.tc.copy(offsetA =
        InstrumentOffset(tag[OffsetP](30.arcseconds), tag[OffsetQ](30.arcseconds)).some
      ),
      gc = TelescopeGuideConfig(
        MountGuideOption.MountGuideOn,
        M1GuideConfig.M1GuideOn(M1Source.GAOS),
        M2GuideConfig.M2GuideOn(ComaOption.ComaOn, Set(TipTiltSource.GAOS))
      ),
      gds = baseConfig.gds.copy(
        aoguide = GemsGuiders(
          tag[CWFS1Config](
            GuiderConfig(ProbeTrackingConfig.Frozen, GuiderSensorOff)
          ),
          tag[CWFS2Config](
            GuiderConfig(ProbeTrackingConfig.Frozen, GuiderSensorOff)
          ),
          tag[CWFS3Config](
            GuiderConfig(ProbeTrackingConfig.Frozen, GuiderSensorOff)
          ),
          tag[ODGW1Config](GuiderConfig(ProbeTrackingConfig.Off, GuiderSensorOff)),
          tag[ODGW2Config](GuiderConfig(ProbeTrackingConfig.Off, GuiderSensorOff)),
          tag[ODGW3Config](GuiderConfig(ProbeTrackingConfig.Off, GuiderSensorOff)),
          tag[ODGW4Config](GuiderConfig(ProbeTrackingConfig.Off, GuiderSensorOff))
        )
      )
    )

    val gemsConfig = GemsOn(
      Cwfs1Usage.Use,
      Cwfs2Usage.Use,
      Cwfs3Usage.Use,
      Odgw1Usage.DontUse,
      Odgw2Usage.DontUse,
      Odgw3Usage.DontUse,
      Odgw4Usage.DontUse,
      P1Usage.Use,
      OIUsage.DontUse
    )

    val gems = new Gems[IO] {
      override val cfg: GemsConfig = gemsConfig

      override def pauseResume(
        pauseReasons:  Gaos.PauseConditionSet,
        resumeReasons: Gaos.ResumeConditionSet
      ): IO[Gaos.PauseResume[IO]] =
        Gaos.PauseResume[IO](IO.unit.some, none).pure[IO]

      override val stateGetter: GemsWfsState[IO] = Gems.GemsWfsState[IO](
        Cwfs1DetectorState.On.pure[IO],
        Cwfs2DetectorState.On.pure[IO],
        Cwfs3DetectorState.On.pure[IO],
        Odgw1DetectorState.Off.pure[IO],
        Odgw2DetectorState.Off.pure[IO],
        Odgw3DetectorState.Off.pure[IO],
        Odgw4DetectorState.Off.pure[IO]
      )

      override def observe(
        config:  Either[AltairController.AltairConfig, GemsConfig],
        expTime: Time
      ): IO[Unit] = IO.unit

      override def endObserve(config: Either[AltairController.AltairConfig, GemsConfig]): IO[Unit] =
        IO.unit
    }

    for {
      d <- dumbEpics
      c  = TcsSouthControllerEpicsAo(d)
      _ <- c.applyAoConfig(TcsController.Subsystem.allButGaosNorOi.add(Subsystem.Gaos),
                           gems,
                           gemsConfig,
                           config
           )
      r <- d.outputF
    } yield {
      val (head, tail) = r.span {
        case TestTcsEvent.OffsetACmd(_, _) => false
        case _                             => true
      }

      assert(guideOffEvents.forall(head.contains))
      assert(!guideOnEvents.forall(tail.contains))
    }

  }

  test("Resume guiding after a sky") {

    val dumbEpics = buildTcsEpics[IO](
      TestTcsEpics.defaultState.copy(
        p1Parked = false,
        pwfs1On = BinaryYesNo.Yes,
        sfName = "gsaoi",
        g1MapName = GemsSource.Cwfs1.some,
        g2MapName = GemsSource.Cwfs2.some,
        g3MapName = GemsSource.Cwfs3.some,
        gsaoiPort = 1,
        useAo = BinaryYesNo.Yes,
        xoffsetPoA1 = 10.0,
        yoffsetPoA1 = 10.0
      )
    )

    val config: TcsSouthAoConfig = baseConfig.copy(
      tc = baseConfig.tc.copy(offsetA =
        InstrumentOffset(tag[OffsetP](0.arcseconds), tag[OffsetQ](0.arcseconds)).some
      ),
      gc = TelescopeGuideConfig(
        MountGuideOption.MountGuideOn,
        M1GuideConfig.M1GuideOn(M1Source.GAOS),
        M2GuideConfig.M2GuideOn(ComaOption.ComaOn, Set(TipTiltSource.GAOS))
      )
    )

    val gemsConfig = GemsOn(
      Cwfs1Usage.Use,
      Cwfs2Usage.Use,
      Cwfs3Usage.Use,
      Odgw1Usage.DontUse,
      Odgw2Usage.DontUse,
      Odgw3Usage.DontUse,
      Odgw4Usage.DontUse,
      P1Usage.Use,
      OIUsage.DontUse
    )

    val gems = new Gems[IO] {
      override val cfg: GemsConfig = gemsConfig

      override def pauseResume(
        pauseReasons:  Gaos.PauseConditionSet,
        resumeReasons: Gaos.ResumeConditionSet
      ): IO[Gaos.PauseResume[IO]] =
        Gaos.PauseResume[IO](IO.unit.some, none).pure[IO]

      override val stateGetter: GemsWfsState[IO] = Gems.GemsWfsState[IO](
        Cwfs1DetectorState.On.pure[IO],
        Cwfs2DetectorState.On.pure[IO],
        Cwfs3DetectorState.On.pure[IO],
        Odgw1DetectorState.Off.pure[IO],
        Odgw2DetectorState.Off.pure[IO],
        Odgw3DetectorState.Off.pure[IO],
        Odgw4DetectorState.Off.pure[IO]
      )

      override def observe(
        config:  Either[AltairController.AltairConfig, GemsConfig],
        expTime: Time
      ): IO[Unit] = IO.unit

      override def endObserve(config: Either[AltairController.AltairConfig, GemsConfig]): IO[Unit] =
        IO.unit
    }

    for {
      d <- dumbEpics
      c  = TcsSouthControllerEpicsAo(d)
      _ <- c.applyAoConfig(TcsController.Subsystem.allButGaosNorOi.add(Subsystem.Gaos),
                           gems,
                           gemsConfig,
                           config
           )
      r <- d.outputF
    } yield {
      val (head, tail) = r.span {
        case TestTcsEvent.OffsetACmd(_, _) => false
        case _                             => true
      }

      assert(!guideOffEvents.forall(head.contains))
      assert(guideOnEvents.forall(tail.contains))
    }

  }

}

object TcsSouthControllerEpicsAoSpec {

  final case class DummyInstrument(id: Instrument, threshold: Option[Length])
      extends InstrumentGuide {
    override val instrument: Instrument = id

    override def oiOffsetGuideThreshold: Option[Length] = threshold
  }

  def buildTcsEpics[F[_]: Async](baseState: TestTcsEpics.State): F[TestTcsEpics[F]] =
    for {
      stR  <- Ref.of[F, TestTcsEpics.State](baseState)
      outR <- Ref.of[F, List[TestTcsEpics.TestTcsEvent]](List.empty)
    } yield TestTcsEpics[F](stR, outR)
}